package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.Money;
import com.dawnline.dispatch.application.port.out.RoutePlanRepository;
import com.dawnline.dispatch.domain.PlanMode;
import com.dawnline.dispatch.domain.PlanModeReason;
import com.dawnline.dispatch.domain.RoutePlan;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

/** {@code route_plans} 영속화 — 모드·사유와 열화 판단 조회 (DESIGN.md §5.3 · §6.7). */
@SpringBootTest(classes = DispatchApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("RoutePlanPersistenceIT — 계획 행")
class RoutePlanPersistenceIT extends DispatchIntegrationTestBase {

    /** 이 클래스는 발행을 보지 않는다 — 릴레이를 끄는 것이 격리다 (ADR-027 후속 정정). */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    /**
     * 운영에 가까운 크기 — 캠프 10개 × 하루 29 웨이브 × 1년 ≈ 10만 행.
     *
     * <p>통계 없는 테이블에서 잰 계획은 아무것도 증명하지 않는다(불변규칙 11). 크기와
     * {@code ANALYZE} 를 함께 갖춰야 플래너가 <em>짐작</em>이 아니라 판단을 한다.
     */
    private static final int OPERATIONAL_ROWS = 100_000;

    /** 시드가 만드는 캠프 수 (부록 A). 캠프가 하나면 조건절이 아무것도 걸러 내지 못한다. */
    private static final int CAMPS = 10;

    /**
     * 이 클래스가 쓰는 캠프 id 는 전부 <strong>0 으로 시작한다.</strong>
     *
     * <p>{@code DELETE FROM route_plans} 를 쓸 수 없기 때문이다 — 이 컨테이너의 DB 를 함께 쓰는
     * 다른 IT 가 남긴 계획에는 {@code routes} 가 붙어 있어 FK 가 막고, 막지 않았다면 <em>남의
     * 행을 지우는</em> 정리가 된다. 정리는 자기 행만 지운다.
     */
    private static final UUID OWNED_CAMPS_END =
            UUID.fromString("00000001-0000-0000-0000-000000000000");

    @Autowired
    private RoutePlanRepository plans;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void clean() {
        deleteAll();
    }

    /**
     * 행만 지우지 않고 <strong>통계까지 되돌린다</strong> — {@code DispatchPersistenceIT} 와 같은
     * 이유다. 10만 행을 넣고 지우기만 하면 {@code reltuples} 가 남아, 이 컨테이너를 함께 쓰는
     * 다음 클래스의 계획을 이 테스트가 정하게 된다.
     */
    @AfterEach
    void resetStatistics() {
        deleteAll();
        analyze();
    }

    private void deleteAll() {
        tx().executeWithoutResult(status -> entityManager
                .createNativeQuery("DELETE FROM route_plans WHERE camp_id < ?")
                .setParameter(1, OWNED_CAMPS_END)
                .executeUpdate());
    }

    @Test
    void 모드와_사유가_함께_왕복한다() {
        // V5. mode 만 남기면 "왜 FAST 였나" 에 답할 수 없다 — 자동 열화와 운영자 지정이
        // 같은 값으로 보인다 (ADR-034).
        Instant now = clock.instant();
        RoutePlan plan = RoutePlan.request(Ids.newId(), Ids.newId(), camp(100),
                GeoPoint.of(37.497900, 127.027600));
        plan.begin("sweep-greedy-nn+ls", PlanMode.FAST, PlanModeReason.LAG, 42L, 1, now);
        tx().executeWithoutResult(status -> plans.insertIfAbsent(plan));
        tx().executeWithoutResult(status -> plans.update(plan));

        RoutePlan loaded = tx().execute(status -> plans.findById(plan.id()).orElseThrow());

        assertThat(loaded.mode()).contains(PlanMode.FAST);
        assertThat(loaded.modeReason()).contains(PlanModeReason.LAG);
    }

    @Test
    void 사유가_없던_계획도_되살아난다() {
        // V5 이전의 행은 근거를 남긴 적이 없고, 그것이 사실이다 — 기본값을 주면 과거의 계획이
        // 전부 "그 사유로 돌았다" 고 말하게 된다.
        RoutePlan plan = RoutePlan.request(Ids.newId(), Ids.newId(), camp(101),
                GeoPoint.of(37.497900, 127.027600));
        tx().executeWithoutResult(status -> plans.insertIfAbsent(plan));

        assertThat(tx().execute(status -> plans.findById(plan.id()).orElseThrow()).modeReason())
                .isEmpty();
    }

    @Test
    void 열화_판단은_같은_캠프의_마지막_발행_계획을_본다() {
        // 캠프별인 이유: 계획 시간은 캠프 규모에 붙어 있다. 큰 캠프의 느린 계획이 작은 캠프를
        // 열화시키면 이 값이 "이 캠프가 밀린다" 가 아니라 "어딘가 바쁘다" 를 뜻하게 된다.
        UUID camp = camp(102);
        UUID otherCamp = camp(103);
        Instant now = clock.instant();

        publishPlan(camp, 1_000, now.minusSeconds(120));
        publishPlan(camp, 25_000, now.minusSeconds(60));
        publishPlan(otherCamp, 29_000, now);

        Optional<Duration> last = tx().execute(status -> plans.lastPublishedDuration(camp));
        Optional<Duration> none = tx().execute(status -> plans.lastPublishedDuration(camp(199)));

        assertThat(last).contains(Duration.ofMillis(25_000));
        assertThat(none).as("발행된 계획이 없으면 그 조건은 발화하지 않는다").isEmpty();
    }

    @Test
    void 발행되지_않은_계획은_직전_계획이_아니다() {
        // PLANNING 으로 죽은 계획의 plan_duration_ms 는 없고, FAILED 는 알고리즘이 끝까지
        // 돈 시간이 아니다. 둘을 세면 열화 판단이 "얼마나 걸렸나" 가 아니라 "무엇이 실패했나"
        // 를 보게 된다.
        UUID camp = camp(104);
        Instant now = clock.instant();
        RoutePlan failed = RoutePlan.request(Ids.newId(), Ids.newId(), camp,
                GeoPoint.of(37.497900, 127.027600));
        failed.begin("baseline-nn", PlanMode.FULL, PlanModeReason.NONE, 1L, 1, now);
        failed.fail("NO_CANDIDATES", now.plusSeconds(1));
        tx().executeWithoutResult(status -> {
            plans.insertIfAbsent(failed);
            plans.update(failed);
        });

        Optional<Duration> last = tx().execute(status -> plans.lastPublishedDuration(camp));

        assertThat(last).isEmpty();
    }

    @Test
    void 열화_판단_조회는_이_규모에서_순차_스캔이_맞다() {
        // 불변규칙 11 — 인덱스를 넣지 <em>않기로 한</em> 판단도 행 수와 함께 기록한다.
        // 기준은 수치를 보기 전에 정했다: 이 질의는 계획마다 한 번 도므로, 계획 시간
        // (large 실측 5,829 ms)의 1% = 58 ms 를 넘으면 인덱스를 넣는다.
        //
        // 측정(docs/benchmarks/phase4-fast-mode.md §6): 10만 행 · 캠프 10개 · ANALYZE 후,
        // 순차 스캔 5.441 ms (계획 시간의 0.09%) 대 (camp_id, finished_at DESC) 인덱스
        // 0.025 ms. 218배 빠르지만 절대값이 기준의 1/10 아래라 인덱스는 유지 비용만 남긴다.
        //
        // 재검토 지점: 이 질의의 비용은 **표 전체 행 수**에 비례한다. 58 ms 는 약 107만 행이고,
        // 캠프 10개 기준 10년치다. 그전에 닿는 길은 캠프 수 증가다 — 캠프 100개면 1년이다.
        // 그래서 재검토 조건은 "캠프 100개" 또는 "route_plans 100만 행" 이다.
        seedPlans(OPERATIONAL_ROWS);
        analyze();

        assertThat(reltuples())
                .as("전제: 통계가 있어야 한다. -1(통계 없음)이면 플래너는 추정치로 짐작하고, "
                        + "그 계획은 무엇도 증명하지 않는다")
                .isGreaterThan(0);
        assertThat(rowCount())
                .as("전제: 운영에 가까운 크기여야 한다").isEqualTo(OPERATIONAL_ROWS);

        // 실재하는 캠프로 잰다. 없는 캠프로 재면 정렬이 0행이라 순차 스캔의 비용을 낮춰
        // 잡고, 그것은 운영에서 일어나는 질의가 아니다.
        String plan = explainLastPublished(camp(3));

        assertThat(plan).as("계획: %s", plan).contains("Seq Scan");
    }

    /** 이 클래스가 소유한 캠프 id. 0~9 는 {@link #seedPlans} 가 대량으로 채우는 값이다. */
    private static UUID camp(int index) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(index));
    }

    /** 발행까지 끝난 계획 하나. */
    private void publishPlan(UUID campId, int durationMs, Instant finishedAt) {
        RoutePlan plan = RoutePlan.request(Ids.newId(), Ids.newId(), campId,
                GeoPoint.of(37.497900, 127.027600));
        plan.begin("baseline-nn", PlanMode.FULL, PlanModeReason.NONE, 1L, 1,
                finishedAt.minusMillis(durationMs));
        plan.complete(Money.krw(1_000), 1, 0, durationMs, finishedAt);
        plan.publish(finishedAt);
        tx().executeWithoutResult(status -> {
            plans.insertIfAbsent(plan);
            plans.update(plan);
        });
    }

    /**
     * 계획 행을 대량으로 넣는다. 캠프를 {@link #CAMPS} 개로 흩뿌리는 이유는
     * {@code DispatchPersistenceIT} 와 같다 — 한 캠프가 표의 큰 몫이면 조건절이 아무것도
     * 걸러 내지 못해 인덱스를 재는 일 자체가 성립하지 않는다.
     */
    private void seedPlans(int count) {
        Instant now = clock.instant();
        tx().executeWithoutResult(status -> entityManager.createNativeQuery("""
                INSERT INTO route_plans
                       (id, wave_id, camp_id, status, strategy, mode, mode_reason, seed,
                        rule_version, started_at, finished_at, total_cost_krw, assigned_count,
                        unassigned_count, plan_duration_ms, version)
                SELECT gen_random_uuid(), gen_random_uuid(),
                       cast('00000000-0000-0000-0000-' ||
                            lpad(cast(i % :campCount as text), 12, '0') as uuid),
                       'PUBLISHED', 'sweep-greedy-nn+ls', 'FULL', 'NONE', i,
                       1, :now, cast(:now as timestamptz) + make_interval(secs => i),
                       1000000, 100, 0, 1200, 0
                  FROM generate_series(1, :count) AS i
                """)
                .setParameter("campCount", CAMPS)
                .setParameter("now", now)
                .setParameter("count", count)
                .executeUpdate());
    }

    private void analyze() {
        tx().executeWithoutResult(status ->
                entityManager.createNativeQuery("ANALYZE route_plans").executeUpdate());
    }

    /** {@code pg_class.reltuples}. 통계가 없으면 -1 이다(PostgreSQL 14+). */
    private float reltuples() {
        return tx().execute(status -> ((Number) entityManager.createNativeQuery("""
                SELECT reltuples FROM pg_class WHERE relname = 'route_plans'
                """).getSingleResult()).floatValue());
    }

    /** 이 클래스가 넣은 행만 센다 — 남이 남긴 행이 전제를 흔들면 안 된다. */
    private long rowCount() {
        return tx().execute(status -> ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM route_plans WHERE camp_id < ?")
                .setParameter(1, OWNED_CAMPS_END)
                .getSingleResult()).longValue());
    }

    /** {@code JpaRoutePlanRepository.LAST_PUBLISHED_SQL} 그대로. */
    @SuppressWarnings("unchecked")
    private String explainLastPublished(UUID campId) {
        return tx().execute(status -> String.join("\n",
                (List<String>) entityManager.createNativeQuery("""
                        EXPLAIN ANALYZE
                        SELECT plan_duration_ms FROM route_plans
                         WHERE camp_id = ? AND status = 'PUBLISHED' AND plan_duration_ms IS NOT NULL
                         ORDER BY finished_at DESC
                         LIMIT 1
                        """).setParameter(1, campId).getResultList()));
    }
}
