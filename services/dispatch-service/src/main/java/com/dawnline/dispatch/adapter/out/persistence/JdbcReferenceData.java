package com.dawnline.dispatch.adapter.out.persistence;

import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.out.DriverLookup;
import com.dawnline.dispatch.application.port.out.RuleCatalog;
import com.dawnline.dispatch.application.port.out.VehicleActivity;
import com.dawnline.dispatch.application.port.out.VehicleCatalog;
import com.dawnline.dispatch.domain.optimizer.Capacity;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.VehicleAttrs;
import com.dawnline.dispatch.domain.optimizer.VehicleCost;
import com.dawnline.dispatch.domain.optimizer.VehicleId;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import com.dawnline.dispatch.domain.optimizer.WaveFleet;
import com.dawnline.dispatch.domain.optimizer.rule.DispatchRules;
import com.dawnline.dispatch.domain.optimizer.rule.RuleDefinition;
import com.dawnline.dispatch.domain.optimizer.rule.RuleSeverity;
import com.dawnline.dispatch.domain.optimizer.rule.RuleType;
import jakarta.persistence.EntityManager;
import java.sql.Time;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 참조 데이터 조회 — 차량·기사·룰·캠프 (DESIGN.md §5.3, §6.3).
 *
 * <p>네 포트를 한 클래스가 구현한다. 넷 다 같은 표들을 읽고 같은 트랜잭션에서 쓰이며, 나누면
 * 같은 SQL 이 네 파일에 흩어진다.
 *
 * <p>캠프 좌표는 여기 없다. {@code wave.closed} 의 {@code depot} 스냅샷으로 들어와
 * {@code route_plans} 에 저장된다(불변규칙 4, V2 마이그레이션) — dispatch 는 캠프의
 * <strong>참조 데이터를 갖지 않는다</strong>.
 */
public class JdbcReferenceData implements VehicleCatalog, VehicleActivity, RuleCatalog, DriverLookup {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 근무창을 붙일 시간대. 컷오프와 같은 기준이다 (§2.2). */
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final EntityManager entityManager;

    /**
     * @param entityManager 공유 EntityManager 프록시
     */
    public JdbcReferenceData(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code ORDER BY id} 로 잠그는 순서를 고정한다 — 발행끼리 서로 막으므로(ADR-075 결정 4) 두 발행이 같은 차량들을 다른
     * 순서로 잡으면 교착이다.
     */
    @Override
    public Set<UUID> lockInactive(Collection<UUID> vehicleIds) {
        Objects.requireNonNull(vehicleIds, "vehicleIds");
        if (vehicleIds.isEmpty()) {
            return Set.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT id, active FROM vehicles WHERE id IN (:ids) ORDER BY id FOR NO KEY UPDATE
                """).setParameter("ids", List.copyOf(new LinkedHashSet<>(vehicleIds))).getResultList();
        Set<UUID> inactive = new LinkedHashSet<>(vehicleIds);
        for (Object[] row : rows) {
            if (Boolean.TRUE.equals(row[1])) {
                inactive.remove((UUID) row[0]);
            }
        }
        return Set.copyOf(inactive);
    }

    @Override
    public Map<UUID, List<TimeWindow>> occupied(Collection<UUID> vehicleIds, UUID waveId) {
        Objects.requireNonNull(vehicleIds, "vehicleIds");
        Objects.requireNonNull(waveId, "waveId");
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT r.vehicle_id, r.planned_departure, r.planned_departure + make_interval(secs => r.duration_s)
                  FROM routes r JOIN route_plans p ON p.id = r.plan_id
                 WHERE r.vehicle_id IN (:ids) AND p.wave_id <> :waveId
                   AND r.planned_departure IS NOT NULL AND r.duration_s > 0
                   AND EXISTS (SELECT 1 FROM route_stops s WHERE s.route_id = r.id AND""" + " "
                        + JdbcDispatchRetention.UNFINISHED_STOP + ")")
                .setParameter("ids", List.copyOf(new LinkedHashSet<>(vehicleIds)))
                .setParameter("waveId", waveId)
                .getResultList();
        Map<UUID, List<TimeWindow>> spans = new LinkedHashMap<>();
        for (Object[] row : rows) {
            spans.computeIfAbsent((UUID) row[0], id -> new ArrayList<>())
                    .add(new TimeWindow(instant(row[1]), instant(row[2])));
        }
        return spans;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code routes.vehicle_id} 에 인덱스가 없어 점유는 {@code routes} 순차 스캔이다 — 계획 하나에 한 번(발행 재검증까지 두 번).
     * 보존 90일에 매일이 피크일이어도 약 11만 행이고, 같은 크기 부류의 순차 스캔이 6.8 ms 로 재졌다(§5.5). 넣지 않은 판단을
     * DESIGN §5.3 에 적었다(불변규칙 11, 근거: 추정 — 이 질의로 재지 않았다).
     */
    @Override
    public List<VehicleSpec> availableAt(UUID campId, Instant planFor, TimeWindow promised, UUID waveId) {
        Objects.requireNonNull(campId, "campId");
        Objects.requireNonNull(promised, "promised");
        Objects.requireNonNull(waveId, "waveId");

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT id, type, max_weight_g, max_volume_cm3, is_cold, allows_hazmat,
                       fixed_cost_krw, cost_per_km_krw, cost_per_min_krw, shift_start, shift_end
                  FROM vehicles WHERE camp_id = ? AND active ORDER BY code
                """).setParameter(1, campId).getResultList();

        Map<UUID, Instant> busy = busyUntil(rows.stream().map(row -> (UUID) row[0]).toList(), waveId, planFor);
        List<VehicleSpec> fleet = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            // shift_start/end 는 벽시계(TIME)다. 날짜를 붙이는 일이 어댑터의 몫이고,
            // 순수 함수는 "몇 시" 가 아니라 "언제" 만 다룬다 (불변규칙 12).
            TimeWindow shift = shiftFor(localTime(row[9]), localTime(row[10]), planFor, promised);
            VehicleSpec spec = new VehicleSpec(VehicleId.of((UUID) row[0]),
                    new Capacity(((Number) row[2]).intValue(), ((Number) row[3]).intValue()),
                    new VehicleAttrs((String) row[1], (Boolean) row[4], (Boolean) row[5]),
                    shift,
                    VehicleCost.krw(((Number) row[6]).longValue(), ((Number) row[7]).longValue(),
                            ((Number) row[8]).longValue()));
            WaveFleet.availableFrom(spec, busy.get((UUID) row[0])).ifPresent(fleet::add);
        }
        return List.copyOf(fleet);
    }

    /**
     * 차량마다 끝나지 않은 발행 라우트 중 가장 늦은 계획 복귀 — <em>다른 웨이브</em>의, <em>이 계획 시각 전에 시작한</em> 계획의 것만
     * (ADR-075 결정 3). 계획 뒤에 시각을 다시 계산하는 경로가 같은 값을 다시 얻어야 한다 — 뒤에 계획된 라우트까지 세면 앞선 라우트가
     * 뒤의 것 뒤로 밀린다.
     */
    private Map<UUID, Instant> busyUntil(List<UUID> vehicleIds, UUID waveId, Instant planFor) {
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT r.vehicle_id, max(r.planned_departure + make_interval(secs => r.duration_s))
                  FROM routes r JOIN route_plans p ON p.id = r.plan_id
                 WHERE r.vehicle_id IN (:ids) AND p.wave_id <> :waveId AND p.started_at < :planFor
                   AND r.planned_departure IS NOT NULL
                   AND EXISTS (SELECT 1 FROM route_stops s WHERE s.route_id = r.id AND""" + " "
                        + JdbcDispatchRetention.UNFINISHED_STOP + """
                )
                 GROUP BY r.vehicle_id
                """)
                .setParameter("ids", vehicleIds)
                .setParameter("waveId", waveId)
                .setParameter("planFor", planFor)
                .getResultList();
        Map<UUID, Instant> busy = new LinkedHashMap<>();
        for (Object[] row : rows) {
            busy.put((UUID) row[0], instant(row[1]));
        }
        return busy;
    }

    /** 드라이버가 {@code TIMESTAMPTZ} 를 {@link Instant} · {@link java.time.OffsetDateTime} · {@link java.sql.Timestamp} 중 무엇으로 주든 받는다. */
    private static Instant instant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case java.time.OffsetDateTime odt -> odt.toInstant();
            case java.sql.Timestamp ts -> ts.toInstant();
            default -> throw new IllegalStateException("시각 컬럼을 읽을 수 없습니다: " + value.getClass());
        };
    }

    @Override
    public RuleSet forCamp(UUID campId) {
        Objects.requireNonNull(campId, "campId");
        // 캠프 오버라이드가 전역을 덮어쓴다 (§6.3). 이름이 같으면 캠프 것이 이긴다.
        Map<String, RuleDefinition> merged = new LinkedHashMap<>();
        readRules(null).forEach(rule -> merged.put(rule.name(), rule));
        readRules(campId).forEach(rule -> merged.put(rule.name(), rule));

        Integer version = (Integer) entityManager.createNativeQuery("""
                SELECT COALESCE(max(rule_version), 1) FROM dispatch_rules
                 WHERE enabled AND (camp_id IS NULL OR camp_id = ?)
                """).setParameter(1, campId).getSingleResult();
        return DispatchRules.ruleSet(List.copyOf(merged.values()), version);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<UUID> driverOf(UUID vehicleId) {
        List<UUID> found = entityManager.createNativeQuery(
                        "SELECT id FROM drivers WHERE vehicle_id = ? ORDER BY code LIMIT 1")
                .setParameter(1, vehicleId)
                .getResultList();
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }


    @SuppressWarnings("unchecked")
    private List<RuleDefinition> readRules(UUID campId) {
        String sql = campId == null
                ? """
                  SELECT name, type, severity, priority, params::text FROM dispatch_rules
                   WHERE enabled AND camp_id IS NULL ORDER BY priority, name
                  """
                : """
                  SELECT name, type, severity, priority, params::text FROM dispatch_rules
                   WHERE enabled AND camp_id = ? ORDER BY priority, name
                  """;
        var query = entityManager.createNativeQuery(sql);
        if (campId != null) {
            query.setParameter(1, campId);
        }
        List<Object[]> rows = query.getResultList();
        List<RuleDefinition> definitions = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Map<String, Object> params =
                    JSON.readValue((String) row[4], new TypeReference<Map<String, Object>>() { });
            definitions.add(new RuleDefinition((String) row[0], RuleType.valueOf((String) row[1]),
                    RuleSeverity.valueOf((String) row[2]), ((Number) row[3]).intValue(), params));
        }
        return definitions;
    }

    private static Instant atDay(LocalDate day, LocalTime time) {
        return day.atTime(time).atZone(ZONE).toInstant();
    }

    /**
     * 벽시계 근무창을 <strong>약속창에 닿는 근무</strong>로 옮긴다 (ADR-075 결정 5).
     *
     * <p>두 가지를 함께 처리한다.
     *
     * <ul>
     *   <li><strong>자정을 넘는 근무조</strong>(예: 23:00–08:00). {@code end <= start} 면 종료는
     *       다음 날이다. 이것을 보지 않으면 {@link TimeWindow} 가 "시작은 종료보다 앞서야" 로
     *       거부한다 — 야간 근무조를 넣는 순간 기동이 아니라 계획이 깨진다.</li>
     *   <li><strong>어느 날의 근무인가.</strong> 약속창 시작의 날짜 기준 전날 · 그날 · 다음 날 가운데 <em>약속창과 겹치고 계획 시각
     *       뒤에 끝나는</em> 첫 번째다. 00:00–07:00 의 새벽 창에는 전날 밤 23:00 에 시작한 조가, 내일 08:00–22:00 창에는 내일의
     *       주간조가 붙는다. 처음 판은 「계획 시각에 아직 끝나지 않은 첫 근무」였고, 14:03 에 조기 마감한 내일의 웨이브가 <em>오늘</em>
     *       주간조를 받았다 — 계획이 언제 도는지에 따라 답이 바뀌는 정의였다.</li>
     * </ul>
     *
     * <p>겹치는 근무가 없으면 계획 시각 뒤에 끝나는 첫 근무를 돌려준다 — 그 차는 {@code WaveFleet.usable} 의 겹침 술어가 뺀다.
     *
     * @param start    근무 시작 벽시계
     * @param end      근무 종료 벽시계
     * @param planFor  계획 시각
     * @param promised 웨이브의 약속창
     */
    static TimeWindow shiftFor(LocalTime start, LocalTime end, Instant planFor, TimeWindow promised) {
        Duration length = Duration.between(start, end);
        if (!length.isPositive()) {
            length = length.plusDays(1);   // 자정을 넘는다
        }
        LocalDate day = promised.start().atZone(ZONE).toLocalDate();
        TimeWindow fallback = null;
        for (LocalDate candidate : List.of(day.minusDays(1), day, day.plusDays(1))) {
            Instant from = atDay(candidate, start);
            Instant to = from.plus(length);
            if (!to.isAfter(planFor)) {
                continue;
            }
            if (from.isBefore(promised.end()) && to.isAfter(promised.start())) {
                return new TimeWindow(from, to);
            }
            if (fallback == null) {
                fallback = new TimeWindow(from, to);
            }
        }
        if (fallback != null) {
            return fallback;
        }
        Instant from = atDay(planFor.atZone(ZONE).toLocalDate().plusDays(1), start);
        return new TimeWindow(from, from.plus(length));
    }

    /**
     * {@code TIME} 컬럼을 읽는다.
     *
     * <p>드라이버가 {@link LocalTime} 을 줄 수도 {@link Time} 을 줄 수도 있다 — 네이티브 질의는
     * 매핑을 거치지 않으므로 그 차이가 그대로 온다. 둘 다 받는다.
     */
    private static LocalTime localTime(Object value) {
        if (value instanceof LocalTime time) {
            return time;
        }
        if (value instanceof Time time) {
            return time.toLocalTime();
        }
        throw new IllegalStateException("근무 시각 컬럼을 읽을 수 없습니다: " + value.getClass());
    }
}
