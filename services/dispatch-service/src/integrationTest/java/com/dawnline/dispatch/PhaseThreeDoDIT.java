package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.DispatchMetrics;
import com.dawnline.dispatch.application.port.in.PlanView;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.PlanQueries;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.observability.DawnlineMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 3 DoD 둘 — <strong>실제 서비스 경로에서</strong> (IMPLEMENTATION_PLAN Phase 3-7).
 *
 * <ul>
 *   <li>냉장 주문이 냉장 차량에만 배정되는가를 <strong>설명 조회로</strong> 확인한다. 하드 룰이
 *       지켜졌다는 것을 단위 테스트가 아니라 운영자가 보는 화면(§5.3 {@code GET /plans/{id}})에서
 *       확인하는 것이 이 DoD 의 요점이다 — 룰을 데이터로 둔 이유가 그 화면이기 때문이다(§6.3).</li>
 *   <li>5,000건이 계획을 <strong>완주</strong>하고 얼마나 걸리는가. 벤치마크 하네스의 `large`
 *       와 다른 것을 잰다: 저쪽은 최적화만, 이쪽은 후보 적재 → 최적화 → 라우트 영속화 →
 *       outbox 기록까지의 왕복이다.</li>
 * </ul>
 */
@SpringBootTest(classes = DispatchApplication.class)
@Import(PlanningClock.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("PhaseThreeDoDIT — 마감 DoD")
class PhaseThreeDoDIT extends DispatchIntegrationTestBase {

    /** 시드의 첫 캠프 (서울 북부). 차량 20대가 여기 붙어 있다. */
    private static final UUID CAMP_ID = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final GeoPoint CAMP = GeoPoint.of(37.640000, 127.030000);

    /**
     * 이 IT 의 계획 예산 — 운영값(30초)이 아니라 <strong>넉넉히</strong> (2026-09-24).
     *
     * <p>여기서 보는 것은 「빠르다」가 아니라 <strong>「이 문제에서 알고리즘이 수렴한다」</strong>다.
     * 운영 예산을 주면 느린 러너에서 마감이 물고, 그러면 수렴 여부가 러너 속도에 섞인다. 넉넉한
     * 예산은 peak 게이트가 「120초 수렴값 = 재현 기준」으로 쓴 방식과 같다. 30초 안에 끝나는지는
     * 참조 기계의 벤치마크가 기록하는 사실이다(§6.7 · §6.9 규칙 2).
     */
    private static final String IT_BUDGET = "120s";

    /**
     * 영속화 경로의 <strong>구조</strong> 상한 — 시간이 아니라 센 값이다(ADR-029 후속 정정, §6.9 게이트 규칙 2).
     *
     * <p>ADR-029 가 막은 회귀는 「느리다」가 아니라 <em>세션에 후보 5,000개가 올라가고 네이티브
     * INSERT 마다 auto-flush 가 도는</em> 모양이었다 — 그때 flush 10,652 · 적재 엔티티 5,001. 벌크
     * 경로에서는 flush 22 · 적재 1 이다. 상한은 그 사이에 둔다: 벌크 경로의 작은 변동(라우트 수)에는
     * 흔들리지 않고, ORM 경로로 돌아가면 <strong>두 자릿수 배</strong>로 넘는다. 이 값들은 러너와
     * 무관하게 같은 입력이면 같다 — seed·주문 id·계획 시각이 고정이기 때문이다.
     */
    private static final long MAX_FLUSHES = 200;

    /** 세션에 적재되는 엔티티 상한 — 벌크 경로는 1, ORM 경로는 후보 수(5,000)다. */
    private static final long MAX_ENTITY_LOADS = 50;

    @Autowired
    private RunPlanUseCase runPlan;

    @Autowired
    private DispatchCandidateRepository candidates;

    @Autowired
    private PlanQueries planQueries;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /**
     * 릴레이는 끈다 — 검사 대상은 발행이 아니다. 발행은 {@code PlanExecutionIT} 가 본다.
     * Hibernate 통계는 <strong>이 클래스에서만</strong> 켠다 — 영속화 게이트가 그 값을 센다.
     */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
        registry.add("dawnline.dispatch.plan.budget", () -> IT_BUDGET);
    }

    @BeforeEach
    void clean() {
        tx().executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM plan_explanations").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_stop_orders").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_stops").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM routes").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_plans").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM dispatch_candidates").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM outbox_events").executeUpdate();
        });
    }

    @Test
    void 냉장_주문은_냉장_차량에만_배정된다_설명_조회로_확인() {
        // 전제 둘. 이것들이 없으면 아래 어설션은 아무것도 검사하지 않는다.
        Set<UUID> coldVehicles = vehiclesWhere(true);
        Set<UUID> warmVehicles = vehiclesWhere(false);
        assertThat(coldVehicles).as("냉장 차량이 없으면 냉장 주문은 애초에 배정될 수 없다").isNotEmpty();
        assertThat(warmVehicles)
                .as("전부 냉장이면 'A 에만 배정된다' 가 자동으로 참이 되어 룰을 검사하지 못한다")
                .isNotEmpty();

        // 400건을 넣는 이유는 아래 마지막 어설션에 있다 — 차량이 여러 대 쓰여야 "냉장 차량에만"
        // 이 규칙의 효과인지 우연인지 구별된다. 24건이면 가장 큰 차 한 대가 전부 삼키고,
        // 그 차가 마침 냉장이면 어설션이 통과하지만 아무것도 증명하지 못한다(2026-09-05에 그랬다).
        UUID waveId = Ids.newId();
        Seeded seeded = seedMixedColdCandidates(waveId, 400);

        tx().executeWithoutResult(status -> runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, CAMP, null)));

        PlanView plan = tx().execute(status -> planQueries.findPlanByWave(waveId)).orElseThrow();
        List<PlanView.ExplanationView> assignedCold = plan.explanations().stream()
                .filter(explanation -> "ASSIGNED".equals(explanation.outcome()))
                .filter(explanation -> seeded.cold().contains(explanation.orderId()))
                .toList();

        // 세 번째 전제: 냉장 주문이 실제로 하나는 배정됐어야 한다. 전부 미배정이면 아래 forall 이
        // 공허하게 참이 된다 — 이 저장소에서 세 번 데었던 그 형태다.
        assertThat(assignedCold).as("냉장 주문이 하나도 배정되지 않으면 검사할 것이 없다").isNotEmpty();
        assertThat(assignedCold)
                .allSatisfy(explanation -> assertThat(coldVehicles)
                        .as("냉장 주문 %s 가 비냉장 차량 %s 에 실렸다 (§6.3 cold-chain 하드 룰)",
                                explanation.orderId(), explanation.vehicleId())
                        .contains(explanation.vehicleId()));

        // 공허함 방지 — 비냉장 차량이 **실제로 쓰였는가**. 계획이 냉장 차량만 열었다면 위
        // 어설션은 자동으로 참이 된다. 비냉장 차량도 열려 있는데 냉장 주문이 거기 없다는 것이
        // 하드 룰의 효과다. (반대 방향인 "상온 주문이 냉장 차량에 실림" 은 룰 위반이 아니다 —
        // cold-chain 은 냉장 주문을 제한한다. 다만 §6.5 3단계의 <strong>좌석 예약</strong>
        // ([ADR-039])이 냉장 차량의 자리 일부를 배정 단계 동안 냉장 수요에 남겨 두므로, 이
        // 어설션은 예약이 들어온 뒤 더 쉽게 참이 된다 — 공허함 방지의 방향은 바뀌지 않는다.)
        Set<UUID> usedVehicles = plan.routes().stream()
                .map(PlanView.RouteSummary::vehicleId)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(usedVehicles)
                .as("비냉장 차량이 한 대도 쓰이지 않으면 '냉장 차량에만' 은 검사되지 않은 것이다")
                .containsAnyElementsOf(warmVehicles);
    }

    @Test
    void 오천건_계획이_예산_안에_끝난다() {
        UUID waveId = Ids.newId();
        seedCandidates(waveId, 5_000);

        // seed 를 고정한다(불변규칙 12). 기본값은 waveId 에서 유도되는데 그 id 가 실행마다 달라
        // 배정 수가 흔들린다 — 마감 문서에 옮겨 적을 수 없는 값이 된다.
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        long convergedBefore = terminations(DispatchMetrics.TERMINATION_CONVERGED);
        long deadlineBefore = terminations(DispatchMetrics.TERMINATION_DEADLINE);
        long startedNanos = System.nanoTime();
        tx().executeWithoutResult(status ->
                runPlan.run(new RunPlanCommand(waveId, CAMP_ID, CAMP, null, null, 20260905L, null)));
        Duration wallClock = Duration.ofNanos(System.nanoTime() - startedNanos);
        long flushes = statistics.getFlushCount();
        long entityLoads = statistics.getEntityLoadCount();
        long preparedStatements = statistics.getPrepareStatementCount();

        PlanView plan = tx().execute(status -> planQueries.findPlanByWave(waveId)).orElseThrow();

        // 측정값을 표준 출력에 남긴다. 마감 문서가 옮겨 적는 값이고, 어설션만 있으면 통과했다는
        // 사실만 남고 *얼마나* 는 사라진다 (§6.9 「환경 없는 수치」와 같은 이유). 시간은 <em>기록만</em>
        // 하므로 환경을 함께 적는다 — 환경 없는 시간은 다른 실행의 시간과 견줄 수 없다.
        Duration persisted = Duration.ofMillis((long) meterRegistry
                .get(DawnlineMetrics.PLAN_PERSIST.meterName()).timer().totalTime(TimeUnit.MILLISECONDS));
        System.out.printf("[Phase 3 DoD] 환경: %s %s · %d 코어 · CI=%s%n", System.getProperty("os.name"),
                System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors(),
                System.getenv().getOrDefault("CI", "false"));
        System.out.printf("[Phase 3 DoD] 5,000건 통합 계획: 왕복 %d ms · 계획 %s ms · 영속화 %d ms · "
                        + "라우트 %d · 배정 %d · 미배정 %d · 비용 %,d원%n",
                wallClock.toMillis(), plan.planDurationMs(), persisted.toMillis(),
                plan.routes().size(), plan.assignedCount(), plan.unassignedCount(),
                plan.totalCostKrw());
        System.out.printf("[Phase 3 DoD] 영속화 구조: flush %d · 적재 엔티티 %d · Hibernate prepared statement %d%n",
                flushes, entityLoads, preparedStatements);

        assertThat(plan.status()).as("완주하지 못하면 시간은 의미가 없다").isEqualTo("PUBLISHED");
        // 게이트는 시간이 아니라 종료 사유다 (2026-09-24). 예전 어설션은 「계획 < 30초」였고 CI 에서
        // 4.3배 여유로 통과했지만, 그것은 러너 속도를 재고 있었다 — 영속화 게이트가 3초를 넘긴 것과
        // 같은 종류다. 수렴은 러너와 무관하게 같은 입력이면 같다.
        assertThat(terminations(DispatchMetrics.TERMINATION_DEADLINE) - deadlineBefore)
                .as("마감(%s)에 잘리지 않았다 — 잘렸다면 결과가 기계 속도에 달린다(ADR-036)", IT_BUDGET)
                .isZero();
        assertThat(terminations(DispatchMetrics.TERMINATION_CONVERGED) - convergedBefore)
                .as("이 계획은 수렴으로 끝났다 (§6.9 재현 조건)")
                .isEqualTo(1);
        // 영속화 게이트는 시간이 아니라 구조를 센다 (ADR-029 후속 정정, 2026-09-24). 처음에는
        // 「영속화 ≤ 3초」였고 로컬 실측이 800 ms 였지만, CI 러너에서는 같은 코드가 789–2,919 ms
        // (3.7배)로 흔들렸고 이웃 모듈의 IT 가 동시에 돌자 3,013 · 3,045 ms 로 넘었다 — 코드가
        // 아니라 러너의 부하였다. §6.9 규칙 2(「게이트는 비용만, 시간은 기록만」)의 예외였던 것이다.
        // ADR-029 가 막은 회귀는 느림이 아니라 모양이었고, 모양은 러너와 무관하게 센다.
        assertThat(flushes)
                .as("flush 횟수 — 벌크 경로 22 근처, ORM 경로로 돌아가면 10,652 (ADR-029)")
                .isLessThanOrEqualTo(MAX_FLUSHES);
        assertThat(entityLoads)
                .as("세션에 적재된 엔티티 — 벌크 경로 1, 후보를 관리 엔티티로 읽으면 5,000 (ADR-029 결정 1)")
                .isLessThanOrEqualTo(MAX_ENTITY_LOADS);
        assertThat(plan.assignedCount() + plan.unassignedCount())
                .as("한 건도 잃지 않는다 — 배정되지 않았으면 미배정으로 세어져야 한다")
                .isEqualTo(5_000);
    }

    /**
     * 결정적인 주문 id — {@code Ids.newId()} 가 아니다.
     *
     * <p>seed 를 고정해도 배정 수가 실행마다 흔들렸다(909 · 914 · 922, 2026-09-05). 원인은 전략의
     * 난수가 아니라 <strong>문제 자체가 매번 달랐던 것</strong>이다: UUIDv7 은 시각에서 나오므로
     * 실행마다 다른 id 가 나오고, stop 통합과 동률 처리의 순서가 그 id 를 따라 바뀐다. 불변규칙 12
     * 의 "seed 가 같으면 결과가 같다" 는 <em>입력이 같을 때</em>의 이야기이고, 벤치마크 하네스는
     * 생성기가 id 까지 만들어 그 조건을 만족시킨다. 여기서는 픽스처가 그 일을 해야 한다.
     *
     * <p>버전 니블 7 · variant 8 을 유지해 UUIDv7 모양을 지킨다(불변규칙 10). 시각 부분이 고정일
     * 뿐이고, 테스트 픽스처에 필요한 것은 시간순 정렬이 아니라 재현성이다.
     *
     * @param prefix 테스트별 접두어 (같은 클래스의 두 테스트가 서로 섞이지 않게)
     * @param index  0 부터의 순번
     */
    private static UUID deterministicOrderId(String prefix, int index) {
        return UUID.fromString("01a07200-%s-7000-8000-%012d".formatted(prefix, index));
    }

    private long terminations(String termination) {
        io.micrometer.core.instrument.Timer timer = meterRegistry.find(DawnlineMetrics.PLAN_DURATION.meterName())
                .tag(DispatchMetrics.TAG_TERMINATION, termination).timer();
        return timer == null ? 0 : timer.count();
    }

    private Set<UUID> vehiclesWhere(boolean cold) {
        @SuppressWarnings("unchecked")
        List<UUID> ids = tx().execute(status -> entityManager.createNativeQuery(
                        "SELECT id FROM vehicles WHERE camp_id = ? AND active AND is_cold = ?")
                .setParameter(1, CAMP_ID)
                .setParameter(2, cold)
                .getResultList());
        return new HashSet<>(ids);
    }

    private record Seeded(Set<UUID> cold, Set<UUID> warm) {
    }

    /**
     * 냉장과 상온을 섞어 넣는다. 격자 위에 흩어 두어 stop 통합이 둘을 한 지점으로 합치지 않게 한다 —
     * 합쳐지면 그 stop 은 통째로 냉장이 되고 검사가 흐려진다.
     */
    private Seeded seedMixedColdCandidates(UUID waveId, int count) {
        Instant now = PlanningClock.PLAN_AT.truncatedTo(ChronoUnit.MICROS);
        TimeWindow window = new TimeWindow(now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)));
        Set<UUID> cold = new HashSet<>();
        Set<UUID> warm = new HashSet<>();
        tx().executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                UUID orderId = deterministicOrderId("c01d", i);
                boolean requiresCold = i % 3 == 0;
                (requiresCold ? cold : warm).add(orderId);
                candidates.insertIfAbsent(DispatchCandidate.load(orderId, waveId, CAMP_ID, null,
                        GeoPoint.of(CAMP.lat() + 0.004d * (i % 8 + 1), CAMP.lng() + 0.005d * (i / 8 + 1)),
                        30_000, 60_000, requiresCold, false, window, 60, false, 0, now));
            }
        });
        return new Seeded(cold, warm);
    }

    private List<UUID> seedCandidates(UUID waveId, int count) {
        Instant now = PlanningClock.PLAN_AT.truncatedTo(ChronoUnit.MICROS);
        TimeWindow window = new TimeWindow(now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(8)));
        List<UUID> orderIds = new ArrayList<>(count);
        tx().executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                UUID orderId = deterministicOrderId("5000", i);
                orderIds.add(orderId);
                candidates.insertIfAbsent(DispatchCandidate.load(orderId, waveId, CAMP_ID, null,
                        GeoPoint.of(CAMP.lat() + 0.0008d * (i % 71), CAMP.lng() + 0.0011d * (i / 71 % 71)),
                        2_500, 6_000, false, false, window, 60, false, 0, now));
            }
        });
        return orderIds;
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
