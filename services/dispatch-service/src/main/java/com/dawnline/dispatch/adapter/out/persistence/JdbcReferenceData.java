package com.dawnline.dispatch.adapter.out.persistence;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.CampLocator;
import com.dawnline.dispatch.application.port.out.DriverLookup;
import com.dawnline.dispatch.application.port.out.RuleCatalog;
import com.dawnline.dispatch.application.port.out.VehicleCatalog;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.Capacity;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.VehicleAttrs;
import com.dawnline.dispatch.domain.optimizer.VehicleCost;
import com.dawnline.dispatch.domain.optimizer.VehicleId;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import com.dawnline.dispatch.domain.optimizer.rule.DispatchRules;
import com.dawnline.dispatch.domain.optimizer.rule.RuleDefinition;
import com.dawnline.dispatch.domain.optimizer.rule.RuleSeverity;
import com.dawnline.dispatch.domain.optimizer.rule.RuleType;
import jakarta.persistence.EntityManager;
import java.sql.Time;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 참조 데이터 조회 — 차량·기사·룰·캠프 (DESIGN.md §5.3, §6.3).
 *
 * <p>네 포트를 한 클래스가 구현한다. 넷 다 같은 표들을 읽고 같은 트랜잭션에서 쓰이며, 나누면
 * 같은 SQL 이 네 파일에 흩어진다.
 *
 * <h2>캠프 좌표의 출처</h2>
 * 캠프는 fulfillment 의 참조 데이터다. dispatch 는 <strong>차량이 붙어 있는 캠프</strong>만
 * 알면 되고, 좌표는 지금 시드가 차량과 함께 넣은 값을 쓴다. 이벤트로 받아 자기 DB 에 투영하는
 * 것이 §4 의 정석이지만 그 이벤트가 설계서에 없다 — {@link CampLocator} 주석에 적어 두었다.
 */
public class JdbcReferenceData implements VehicleCatalog, RuleCatalog, DriverLookup, CampLocator {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 근무창을 붙일 시간대. 컷오프와 같은 기준이다 (§2.2). */
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final EntityManager entityManager;
    private final Map<UUID, GeoPoint> campPoints;

    /**
     * @param entityManager 공유 EntityManager 프록시
     * @param campPoints    캠프 좌표. fulfillment 시드와 같은 값이다
     */
    public JdbcReferenceData(EntityManager entityManager, Map<UUID, GeoPoint> campPoints) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.campPoints = Map.copyOf(Objects.requireNonNull(campPoints, "campPoints"));
    }

    @Override
    public List<VehicleSpec> availableAt(UUID campId, Instant planFor) {
        Objects.requireNonNull(campId, "campId");
        LocalDate day = planFor.atZone(ZONE).toLocalDate();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT id, type, max_weight_g, max_volume_cm3, is_cold, allows_hazmat,
                       fixed_cost_krw, cost_per_km_krw, cost_per_min_krw, shift_start, shift_end
                  FROM vehicles WHERE camp_id = ? AND active ORDER BY code
                """).setParameter(1, campId).getResultList();

        List<VehicleSpec> fleet = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            // shift_start/end 는 벽시계(TIME)다. 계획 날짜에 붙이는 일이 어댑터의 몫이고,
            // 순수 함수는 "몇 시" 가 아니라 "언제" 만 다룬다 (불변규칙 12).
            Instant start = atDay(day, localTime(row[9]));
            Instant end = atDay(day, localTime(row[10]));
            fleet.add(new VehicleSpec(VehicleId.of((UUID) row[0]),
                    new Capacity(((Number) row[2]).intValue(), ((Number) row[3]).intValue()),
                    new VehicleAttrs((String) row[1], (Boolean) row[4], (Boolean) row[5]),
                    new TimeWindow(start, end),
                    VehicleCost.krw(((Number) row[6]).longValue(), ((Number) row[7]).longValue(),
                            ((Number) row[8]).longValue())));
        }
        return List.copyOf(fleet);
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

    @Override
    public CampDepot locate(UUID campId) {
        GeoPoint point = campPoints.get(campId);
        if (point == null) {
            throw new IllegalStateException(
                    "캠프 좌표를 모릅니다: %s (참조 데이터 시드를 확인하세요)".formatted(campId));
        }
        return new CampDepot(campId, point);
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
