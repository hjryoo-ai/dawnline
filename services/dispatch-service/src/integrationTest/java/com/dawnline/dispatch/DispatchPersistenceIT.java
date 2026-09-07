package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

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
                new TimeWindow(NOW, NOW.plus(Duration.ofHours(4))), 120, 2, NOW);
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
                        promised_start, promised_end, service_seconds, status, created_at, updated_at)
                SELECT gen_random_uuid(), coalesce(cast(:waveId as uuid), gen_random_uuid()),
                       gen_random_uuid(), 37.497900, 127.027600, 'wydm9qy', 1234, 5678,
                       :now, :promisedEnd, 120, 'PENDING', :now, :now
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
                new TimeWindow(NOW, NOW.plusSeconds(3600)), 60, 0, NOW);
        tx().executeWithoutResult(status -> candidates.insertIfAbsent(saved));

        DispatchCandidate loaded = tx().execute(status ->
                candidates.findById(saved.orderId()).orElseThrow());

        assertThat(loaded.location().lat()).isEqualTo(37.497901);
        assertThat(loaded.location().lng()).isEqualTo(127.027600);
    }
}
