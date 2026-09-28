package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.domain.CandidateStatus;
import com.dawnline.dispatch.domain.DispatchCandidate;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@code dispatch_candidates} 영속화 (DESIGN.md §5.3). */
@SpringBootTest(classes = DispatchApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("DispatchPersistenceIT — 계획 후보")
class DispatchPersistenceIT extends DispatchIntegrationTestBase {

    /**
     * 릴레이를 끈다 — 이 클래스는 발행을 보지 않는다.
     *
     * <p>끄는 것이 <strong>격리</strong>다. 리더 락이 advisory lock 이 된 뒤(ADR-027 후속 정정)
     * 이 컨테이너의 한 데이터베이스에 대해 릴레이는 <em>한 컨텍스트만</em> 리더가 된다. 스프링은
     * 컨텍스트를 캐시하므로 먼저 뜬 클래스의 릴레이가 락을 계속 쥐고, 그러면 실제로 발행을 보는
     * {@code PlanExecutionIT} 가 팔로워가 되어 아무것도 못 본다. 순서에 달린 실패다.
     *
     * <p>이전에는 이 문제가 보이지 않았다 — 리더 락이 Redis 였고 이 컨텍스트들에는 Redis 가
     * 없어서 전부 판정 불가(발행 안 함)였기 때문이다. <strong>격리가 락의 무력함에 기대고
     * 있었다.</strong>
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    private static final Instant NOW =
            Instant.parse("2026-09-06T01:00:00Z").truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private DispatchCandidateRepository candidates;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void clean() {
        tx().executeWithoutResult(status ->
                entityManager.createNativeQuery("DELETE FROM dispatch_candidates").executeUpdate());
    }

    /**
     * 행만 지우지 않고 <strong>통계까지 되돌린다.</strong>
     *
     * <p>이 클래스는 인덱스 계획을 보려고 20,000행을 넣는다(§5.3 측정). 행을 지워도
     * {@code pg_class.reltuples} 는 20,000 인 채로 남아, 이 컨테이너의 dispatch DB 를 함께 쓰는
     * 다음 클래스의 계획을 <em>이 테스트가</em> 정하게 된다. 그것이 바로 이 클래스가 고치고 있는
     * 결함이라, 여기서 만들어 낸 통계는 여기서 치운다.
     */
    @AfterEach
    void resetStatistics() {
        tx().executeWithoutResult(status ->
                entityManager.createNativeQuery("DELETE FROM dispatch_candidates").executeUpdate());
        analyzeCandidates();
    }

    private static DispatchCandidate candidate(UUID waveId) {
        return DispatchCandidate.load(Ids.newId(), waveId, Ids.newId(), Ids.newId(),
                GeoPoint.of(37.497900, 127.027600), 1_234, 5_678, true, false,
                new TimeWindow(NOW, NOW.plus(Duration.ofHours(4))), 120, true, 2, NOW);
    }

    @Test
    void 모든_컬럼이_왕복한다() {
        DispatchCandidate saved = candidate(Ids.newId());
        tx().executeWithoutResult(status -> candidates.insertIfAbsent(saved));

        DispatchCandidate loaded = tx().execute(status ->
                candidates.findById(saved.orderId()).orElseThrow());

        assertThat(loaded.waveId()).isEqualTo(saved.waveId());
        assertThat(loaded.campId()).isEqualTo(saved.campId());
        assertThat(loaded.zoneId()).isEqualTo(saved.zoneId());
        assertThat(loaded.location().lat()).isEqualTo(saved.location().lat());
        assertThat(loaded.location().lng()).isEqualTo(saved.location().lng());
        assertThat(loaded.weightG()).isEqualTo(1_234);
        assertThat(loaded.volumeCm3()).isEqualTo(5_678);
        assertThat(loaded.requiresCold()).isTrue();
        assertThat(loaded.hazmat()).isFalse();
        assertThat(loaded.promised()).isEqualTo(saved.promised());
        assertThat(loaded.serviceSeconds()).isEqualTo(120);
        assertThat(loaded.priority()).isEqualTo(2);
        assertThat(loaded.status()).isEqualTo(CandidateStatus.PENDING);
    }

    @Test
    void 취소_선착_표식은_스냅샷_없이_들어가고_후보로_보이지_않는다() {
        // ADR-074 결정 1 · 3 — 먼저 온 취소가 행을 만들고, 뒤에 온 적재는 그 행을 되살리지 않는다.
        DispatchCandidate late = candidate(Ids.newId());
        UUID orderId = late.orderId();

        boolean marked = tx().execute(status -> candidates.insertCancelledFirst(orderId, NOW));
        boolean again = tx().execute(status -> candidates.insertCancelledFirst(orderId, NOW));
        boolean loaded = tx().execute(status -> candidates.insertIfAbsent(late));

        assertThat(marked).isTrue();
        assertThat(again).as("표식은 한 번").isFalse();
        assertThat(loaded).as("뒤에 온 적재는 넣지 못한다 — 같은 PK").isFalse();
        Optional<DispatchCandidate> found = tx().execute(status -> candidates.findById(orderId));
        Object shape = tx().execute(status -> entityManager.createNativeQuery(
                        "SELECT status || '|' || (wave_id IS NULL) FROM dispatch_candidates WHERE order_id = ?")
                .setParameter(1, orderId).getSingleResult());
        assertThat(found).as("스냅샷이 없는 행은 후보로 되살리지 않는다").isEmpty();
        assertThat(shape).isEqualTo("CANCELLED|true");
    }

    @Test
    void 후보가_먼저_있으면_표식을_넣지_않는다() {
        // 결정 4 — 두 리스너의 경합을 PK 가 심판한다. 표식을 넣지 못한 취소는 다시 읽어 후보를 취소한다.
        DispatchCandidate saved = candidate(Ids.newId());
        tx().executeWithoutResult(status -> candidates.insertIfAbsent(saved));

        boolean marked = tx().execute(status -> candidates.insertCancelledFirst(saved.orderId(), NOW));

        assertThat(marked).isFalse();
        Optional<DispatchCandidate> found = tx().execute(status -> candidates.findById(saved.orderId()));
        assertThat(found).hasValueSatisfying(candidate ->
                assertThat(candidate.status()).isEqualTo(CandidateStatus.PENDING));
    }

    /**
     * 행의 모양은 둘뿐이다 — 스냅샷 칸이 전부 있거나, {@code CANCELLED} 이고 전부 없다(V15 CHECK, ADR-074 결정 3).
     *
     * <p>스냅샷 칸을 <strong>열거하지 않는다</strong> — 표의 칸 전부에서 {@link #NOT_SNAPSHOT} 을 뺀다. 칸이 늘면 이 테스트가
     * 그 칸의 값을 몰라 실패하고({@link #SAMPLE}), CHECK 에 넣지 않았으면 그 칸만 비운 행이 들어가 실패한다(재검토 지점 1).
     */
    @Test
    void 행의_모양은_스냅샷_전부와_취소_선착_표식_둘뿐이다() {
        List<String> snapshot = snapshotColumns();
        assertThat(insertRaw("PENDING", full(snapshot))).as("전제 — 온전한 스냅샷 행은 들어간다").isNull();
        assertThat(insertRaw("CANCELLED", empty(snapshot))).as("전제 — 표식은 들어간다").isNull();

        for (String column : snapshot) {
            Map<String, String> missing = full(snapshot);
            missing.put(column, "NULL");
            assertThat(insertRaw("PENDING", missing)).as("스냅샷 행에서 %s 하나만 비웠다", column)
                    .hasStackTraceContaining(SHAPE_CHECK);
            Map<String, String> filled = empty(snapshot);
            filled.put(column, SAMPLE.get(column));
            assertThat(insertRaw("CANCELLED", filled)).as("표식에 %s 하나만 채웠다", column)
                    .hasStackTraceContaining(SHAPE_CHECK);
        }
        Map<String, String> zoned = empty(snapshot);
        zoned.put("zone_id", "'" + Ids.newId() + "'");
        assertThat(insertRaw("CANCELLED", zoned)).as("표식의 권역도 모른다").hasStackTraceContaining(SHAPE_CHECK);
        assertThat(insertRaw("PENDING", empty(snapshot))).as("스냅샷 없는 행은 취소 선착뿐이다")
                .hasStackTraceContaining(SHAPE_CHECK);
    }

    @Test
    void 스냅샷에서_뺀_칸은_NOT_NULL_이거나_zone_id_다() {
        // 위 테스트가 뺀 칸이 왜 빠졌는지 — 행의 신원 · 상태 · 시각은 두 모양 모두 가지므로 NOT NULL 이 지키고,
        // zone_id 는 스냅샷 행에서도 NULL 일 수 있어(지오코딩 실패) 표식 쪽 CHECK 에서만 본다(위 테스트의 「표식의 권역」).
        Map<String, String> nullable = new java.util.TreeMap<>();
        List<?> rows = tx().execute(status -> entityManager.createNativeQuery("""
                        SELECT column_name || '|' || is_nullable FROM information_schema.columns
                         WHERE table_name = 'dispatch_candidates'""").getResultList());
        for (Object row : java.util.Objects.requireNonNull(rows, "rows")) {
            String[] parts = ((String) row).split("\\|");
            nullable.put(parts[0], parts[1]);
        }
        assertThat(nullable).containsKeys(NOT_SNAPSHOT.toArray(String[]::new));
        NOT_SNAPSHOT.stream().filter(column -> !column.equals("zone_id")).forEach(column ->
                assertThat(nullable.get(column)).as("%s 는 두 모양 모두 가진다", column).isEqualTo("NO"));
        assertThat(nullable.get("zone_id")).isEqualTo("YES");
    }

    private static final String SHAPE_CHECK = "ck_cand_snapshot_or_cancelled_first";

    /** 스냅샷이 아닌 칸 — 까닭은 {@code 스냅샷에서_뺀_칸은_NOT_NULL_이거나_zone_id_다} 가 본다. */
    private static final List<String> NOT_SNAPSHOT =
            List.of("order_id", "status", "version", "created_at", "updated_at", "zone_id");

    /** 스냅샷 칸의 표본 값(SQL 리터럴). DB 왕복만 하는 픽스처라 시각 리터럴이어도 된다(CLAUDE.md). */
    private static final Map<String, String> SAMPLE = Map.ofEntries(
            Map.entry("wave_id", "'" + Ids.newId() + "'"),
            Map.entry("camp_id", "'" + Ids.newId() + "'"),
            Map.entry("lat", "37.500000"),
            Map.entry("lng", "127.000000"),
            Map.entry("geohash7", "'wydm9qy'"),
            Map.entry("weight_g", "1"),
            Map.entry("volume_cm3", "1"),
            Map.entry("requires_cold", "false"),
            Map.entry("hazmat", "false"),
            Map.entry("promised_start", "'2026-09-06T01:00:00Z'"),
            Map.entry("promised_end", "'2026-09-06T05:00:00Z'"),
            Map.entry("service_seconds", "60"),
            Map.entry("promise_revised", "false"),
            Map.entry("priority", "0"));

    private List<String> snapshotColumns() {
        List<?> columns = tx().execute(status -> entityManager.createNativeQuery("""
                        SELECT column_name FROM information_schema.columns
                         WHERE table_name = 'dispatch_candidates' ORDER BY ordinal_position""").getResultList());
        List<String> snapshot = java.util.Objects.requireNonNull(columns, "columns").stream().map(String.class::cast)
                .filter(column -> !NOT_SNAPSHOT.contains(column)).toList();
        assertThat(snapshot).as("전제 — 스냅샷 칸이 있다").isNotEmpty();
        return snapshot;
    }

    private static Map<String, String> full(List<String> snapshot) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (String column : snapshot) {
            assertThat(SAMPLE).as("스냅샷 칸 %s 의 표본 값을 이 테스트가 모른다 — CHECK 와 SAMPLE 에 함께 더한다", column)
                    .containsKey(column);
            values.put(column, SAMPLE.get(column));
        }
        return values;
    }

    private static Map<String, String> empty(List<String> snapshot) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        snapshot.forEach(column -> values.put(column, "NULL"));
        return values;
    }

    /** 한 행을 제 트랜잭션에서 넣는다 — 거절되면 그 예외, 들어가면 {@code null}. */
    private @Nullable Throwable insertRaw(String status, Map<String, String> snapshot) {
        Map<String, String> row = new java.util.LinkedHashMap<>();
        row.put("order_id", "'" + Ids.newId() + "'");
        row.put("status", "'" + status + "'");
        row.put("version", "0");
        row.put("created_at", "'" + NOW + "'");
        row.put("updated_at", "'" + NOW + "'");
        row.putAll(snapshot);
        String sql = "INSERT INTO dispatch_candidates (" + String.join(", ", row.keySet()) + ") VALUES ("
                + String.join(", ", row.values()) + ")";
        return catchThrowable(() -> tx().executeWithoutResult(transaction ->
                entityManager.createNativeQuery(sql).executeUpdate()));
    }

    @Test
    void 같은_주문은_두_번_들어가지_않는다() {
        // ON CONFLICT DO NOTHING 이다. 조회 후 저장으로 흉내 내면 동시 수신에서 둘 다 넣는다.
        DispatchCandidate first = candidate(Ids.newId());

        boolean inserted = tx().execute(status -> candidates.insertIfAbsent(first));
        boolean again = tx().execute(status -> candidates.insertIfAbsent(first));

        assertThat(inserted).isTrue();
        assertThat(again).isFalse();
    }

    @Test
    void 웨이브의_계획_대상만_모은다() {
        UUID waveId = Ids.newId();
        DispatchCandidate pending = candidate(waveId);
        DispatchCandidate planned = candidate(waveId);
        DispatchCandidate otherWave = candidate(Ids.newId());
        tx().executeWithoutResult(status -> {
            candidates.insertIfAbsent(pending);
            candidates.insertIfAbsent(planned);
            candidates.insertIfAbsent(otherWave);
        });
        tx().executeWithoutResult(status -> {
            DispatchCandidate found = candidates.findById(planned.orderId()).orElseThrow();
            found.recordPlanResult(CandidateStatus.PLANNED, NOW);
            candidates.update(found);
        });

        List<DispatchCandidate> plannable =
                tx().execute(status -> candidates.findPlannableInWave(waveId));

        assertThat(plannable).extracting(DispatchCandidate::orderId)
                .containsExactly(pending.orderId());
    }

    @Test
    void 취소해도_행과_소속이_남는다() {
        // ADR-026 — 지우면 "주문 X 는 왜 라우트에 없나" 에 답할 수 없다.
        DispatchCandidate saved = candidate(Ids.newId());
        tx().executeWithoutResult(status -> candidates.insertIfAbsent(saved));
        tx().executeWithoutResult(status -> {
            DispatchCandidate found = candidates.findById(saved.orderId()).orElseThrow();
            found.cancel(NOW.plusSeconds(60));
            candidates.update(found);
        });

        DispatchCandidate loaded = tx().execute(status ->
                candidates.findById(saved.orderId()).orElseThrow());

        assertThat(loaded.status()).isEqualTo(CandidateStatus.CANCELLED);
        assertThat(loaded.waveId()).isEqualTo(saved.waveId());
        assertThat(loaded.location()).isEqualTo(saved.location());
    }

    @Test
    void 계획_결과는_집합으로_반영되고_취소를_뒤집지_않는다() {
        // ADR-029 — 벌크 UPDATE 의 `AND status = 'PENDING'` 이 애그리거트의 축 규칙
        // (recordPlanResult 의 !hasProgressedPast)을 옮겨 적은 것이다. 같은 규칙을 두 곳이
        // 적으므로 어긋나면 조용하다. 그래서 둘을 한 테스트에서 나란히 확인한다.
        UUID waveId = Ids.newId();
        DispatchCandidate pending = candidate(waveId);
        DispatchCandidate cancelled = candidate(waveId);
        tx().executeWithoutResult(status -> {
            candidates.insertIfAbsent(pending);
            candidates.insertIfAbsent(cancelled);
        });
        tx().executeWithoutResult(status -> {
            DispatchCandidate loaded = candidates.findById(cancelled.orderId()).orElseThrow();
            loaded.cancel(NOW.plusSeconds(30));
            candidates.update(loaded);
        });

        // 전제 — 애그리거트는 취소된 후보의 계획 결과 반영을 거부한다. 이것이 SQL 이 지켜야
        // 할 규칙이고, 여기서 확인하지 않으면 아래 어설션은 SQL 만 보는 것이 된다.
        DispatchCandidate cancelledSnapshot =
                tx().execute(status -> candidates.findById(cancelled.orderId()).orElseThrow());
        assertThat(cancelledSnapshot.recordPlanResult(CandidateStatus.PLANNED, NOW.plusSeconds(60)))
                .as("전제: 애그리거트는 CANCELLED 를 PLANNED 로 되돌리지 않는다 (ADR-026)")
                .isFalse();

        int changed = tx().execute(status -> candidates.recordPlanResult(
                List.of(pending.orderId(), cancelled.orderId()),
                CandidateStatus.PLANNED, NOW.plusSeconds(60)));

        assertThat(changed).as("PENDING 하나만 전이해야 한다").isEqualTo(1);
        assertThat(tx().execute(status -> candidates.findById(pending.orderId()).orElseThrow())
                .status()).isEqualTo(CandidateStatus.PLANNED);
        assertThat(tx().execute(status -> candidates.findById(cancelled.orderId()).orElseThrow())
                .status())
                .as("취소된 후보가 집합 UPDATE 로 뒤집히면 ADR-026 이 깨진다")
                .isEqualTo(CandidateStatus.CANCELLED);
    }

    @Test
    void 계획_대상_조회가_인덱스를_탄다() {
        // ix_cand_wave (wave_id, status) — 계획이 "이 웨이브의 PENDING 후보" 를 집는 질의가
        // 유일한 뜨거운 경로다(§5.3). 계획이 실제로 도는 모양 그대로 본다: JPQL 의
        // ORDER BY c.orderId 까지 포함해서다. 빼고 재면 서비스가 돌리지 않는 계획을 인증한다.
        //
        // 크기와 통계를 함께 갖춰야 무언가를 증명한다. 갓 만든 테이블은 pg_class.reltuples = -1
        // (통계 없음)이고 그때 플래너는 기본 추정치로 짐작하는데, 그 짐작이 50행짜리 테이블에서도
        // 인덱스를 고른다. 이 테스트는 그 짐작을 통과로 읽고 있었고, CI 에서 autoanalyze 가 먼저
        // 돌자 순차 스캔이 나와 깨졌다 — 50행에서는 순차 스캔이 맞는 판단이다.
        // 그래서 운영에 가까운 크기까지 채우고 ANALYZE 한 뒤에 본다(FulfillmentPersistenceIT 의
        // 마감_대상_조회가_부분_인덱스를_탄다 와 같은 형태, 측정은
        // docs/benchmarks/phase4-dispatch-candidates-index.md).
        UUID waveId = Ids.newId();
        seedCandidates(waveId, 50);
        seedCandidates(null, 19_950);
        analyzeCandidates();

        assertThat(reltuples())
                .as("전제: 통계가 있어야 한다. -1(통계 없음)이면 플래너는 추정치로 짐작하고, "
                        + "그 계획은 인덱스가 값을 한다는 것을 증명하지 않는다")
                .isGreaterThan(0);
        assertThat(rowCount())
                .as("전제: 교차점(측정값 350↔400행) 위여야 한다. 그 아래에서는 순차 스캔이 맞다")
                .isEqualTo(20_000);

        String plan = explainPlannableInWave(waveId);

        assertThat(plan).as("계획: %s", plan).contains("ix_cand_wave");
    }

    /**
     * 후보를 대량으로 넣는다. {@code waveId} 가 {@code null} 이면 웨이브를 행마다 다르게 흩뿌린다 —
     * 한 웨이브가 테이블의 큰 몫이면 순차 스캔이 맞는 판단이 되어(측정: 비중 50% 에서 순차 스캔,
     * 20% 부터 인덱스) 인덱스를 재는 일 자체가 성립하지 않는다.
     */
    private void seedCandidates(@Nullable UUID waveId, int count) {
        tx().executeWithoutResult(status -> entityManager.createNativeQuery("""
                INSERT INTO dispatch_candidates
                       (order_id, wave_id, camp_id, lat, lng, geohash7, weight_g, volume_cm3,
                        requires_cold, hazmat, promised_start, promised_end, service_seconds,
                        promise_revised, priority, status, created_at, updated_at)
                SELECT gen_random_uuid(), coalesce(cast(:waveId as uuid), gen_random_uuid()),
                       gen_random_uuid(), 37.497900, 127.027600, 'wydm9qy', 1234, 5678,
                       false, false, :now, :promisedEnd, 120, false, 0, 'PENDING', :now, :now
                  FROM generate_series(1, :count)
                """)
                .setParameter("waveId", waveId == null ? null : waveId.toString())
                .setParameter("now", NOW)
                .setParameter("promisedEnd", NOW.plus(Duration.ofHours(4)))
                .setParameter("count", count)
                .executeUpdate());
    }

    /** 통계가 없으면 플래너는 기본 추정치로 판단한다 — 계획을 보려면 갱신이 먼저다. */
    private void analyzeCandidates() {
        tx().executeWithoutResult(status ->
                entityManager.createNativeQuery("ANALYZE dispatch_candidates").executeUpdate());
    }

    /** {@code pg_class.reltuples}. 통계가 없으면 -1 이다(PostgreSQL 14+). */
    private float reltuples() {
        return tx().execute(status -> ((Number) entityManager.createNativeQuery("""
                SELECT reltuples FROM pg_class WHERE relname = 'dispatch_candidates'
                """).getSingleResult()).floatValue());
    }

    private long rowCount() {
        return tx().execute(status -> ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM dispatch_candidates")
                .getSingleResult()).longValue());
    }

    /** {@code JpaDispatchCandidateRepository.FIND_PLANNABLE_JPQL} 이 만드는 모양 그대로. */
    @SuppressWarnings("unchecked")
    private String explainPlannableInWave(UUID waveId) {
        return tx().execute(status -> String.join("\n",
                (List<String>) entityManager.createNativeQuery("""
                        EXPLAIN SELECT * FROM dispatch_candidates
                         WHERE wave_id = ? AND status = 'PENDING'
                         ORDER BY order_id
                        """).setParameter(1, waveId).getResultList()));
    }

    @Test
    void 좌표가_저장_정밀도로_왕복한다() {
        // NUMERIC(9,6) 이다. 자르지 않으면 저장 전후 값이 달라져 거리 계산이 미세하게 어긋난다.
        DispatchCandidate saved = DispatchCandidate.load(Ids.newId(), Ids.newId(), Ids.newId(), null,
                GeoPoint.of(37.4979009, 127.0276001), 1, 1, false, false,
                new TimeWindow(NOW, NOW.plusSeconds(3600)), 60, false, 0, NOW);
        tx().executeWithoutResult(status -> candidates.insertIfAbsent(saved));

        DispatchCandidate loaded = tx().execute(status ->
                candidates.findById(saved.orderId()).orElseThrow());

        assertThat(loaded.location().lat()).isEqualTo(37.497901);
        assertThat(loaded.location().lng()).isEqualTo(127.027600);
    }
}
