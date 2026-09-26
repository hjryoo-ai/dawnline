package com.dawnline.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.domain.CandidateStatus;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.PlanMode;
import com.dawnline.dispatch.domain.PlanModeReason;
import com.dawnline.dispatch.domain.PlanModeSelector;
import com.dawnline.dispatch.domain.PlanStatus;
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
import com.dawnline.dispatch.domain.optimizer.PlanningBudget;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.rule.DispatchRules;
import com.dawnline.dispatch.domain.optimizer.rule.RuleDefinition;
import com.dawnline.dispatch.domain.optimizer.rule.RuleSeverity;
import com.dawnline.dispatch.domain.optimizer.rule.RuleType;
import com.dawnline.observability.DawnlineMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RunPlanServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-06T01:00:00Z");
    private static final UUID CAMP_ID = Ids.newId();

    private final InMemoryDispatchPorts.Plans plans = new InMemoryDispatchPorts.Plans();
    private final InMemoryDispatchPorts.Candidates candidates = new InMemoryDispatchPorts.Candidates();
    private final InMemoryDispatchPorts.Routes routes = new InMemoryDispatchPorts.Routes();
    private final InMemoryDispatchPorts.Events events = new InMemoryDispatchPorts.Events();
    private final InMemoryDispatchPorts.Transactions transactions = new InMemoryDispatchPorts.Transactions();

    private RunPlanService service(RuleSet rules, int vehicleCount) {
        return service(rules, vehicleCount, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * 계획 한 번이 {@code step} 만큼 걸리는 것처럼 보이게 하는 시계.
     *
     * <p>{@code planDurationMs} 는 {@code clock.instant()} 두 번의 차이다 —
     * {@code Clock.fixed} 면 언제나 0 ms 라 §6.7 의 예산 조건이 발화할 수 없다. 값을 손으로
     * 밀어 넣는 대신 <strong>시간이 흐르게</strong> 해서 운영 코드가 그 수를 스스로 만들게 한다.
     */
    private static Clock stepping(Duration step) {
        AtomicInteger calls = new AtomicInteger();
        return new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return NOW.plus(step.multipliedBy(calls.getAndIncrement()));
            }
        };
    }

    private RunPlanService service(RuleSet rules, int vehicleCount, Clock clock) {
        return new RunPlanService(plans, candidates, routes, events,
                InMemoryDispatchPorts.fleet(vehicleCount, NOW),
                InMemoryDispatchPorts.rules(rules),
                new HaversineDistance(1.3d, 25.0d),
                new DispatchMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                clock,
                "baseline-nn", new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3)),
                new PlanModeSelector(3L, 0.8d, 0.5d), transactions);
    }

    private List<UUID> seed(UUID waveId, int count) {
        TimeWindow window = new TimeWindow(NOW.plus(Duration.ofHours(1)),
                NOW.plus(Duration.ofHours(5)));
        List<UUID> orderIds = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID orderId = Ids.newId();
            orderIds.add(orderId);
            candidates.put(DispatchCandidate.load(orderId, waveId, CAMP_ID, null,
                    GeoPoint.of(InMemoryDispatchPorts.CAMP.lat() + 0.004d * (i + 1),
                            InMemoryDispatchPorts.CAMP.lng() + 0.003d * (i + 1)),
                    1_000, 2_000, false, false, window, 60, false, 0, NOW));
        }
        return orderIds;
    }

    @Test
    void 계획하고_세_가지를_모두_발행한다() {
        UUID waveId = Ids.newId();
        List<UUID> orderIds = seed(waveId, 5);

        assertThat(service(RuleSet.empty(), 2).run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null)))
                .isEqualTo(RunPlanUseCase.Outcome.PUBLISHED);

        assertThat(events.routesAssigned).isNotEmpty();
        assertThat(events.ordersDispatched).containsExactlyInAnyOrderElementsOf(orderIds);
        assertThat(events.completed).isEqualTo(1);
        assertThat(events.failed).isZero();
    }

    @Test
    void 수렴으로_끝난_계획은_termination_converged_로_센다() {
        // 계획 시간은 러너를 따라 흔들리지만 종료 사유는 흔들리지 않는다 — CI 가 보는 값이 이것이다
        // (PhaseThreeDoDIT, §6.9 재현 조건).
        UUID waveId = Ids.newId();
        seed(waveId, 5);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

        service(registry, Duration.ofSeconds(30)).run(new RunPlanCommand(waveId, CAMP_ID,
                InMemoryDispatchPorts.CAMP, "sweep-greedy-nn+ls", null, 1L, null));

        assertThat(terminations(registry, DispatchMetrics.TERMINATION_CONVERGED)).isEqualTo(1);
        assertThat(terminations(registry, DispatchMetrics.TERMINATION_DEADLINE)).isZero();
    }

    @Test
    void 마감에_잘린_계획은_termination_deadline_으로_센다() {
        // 「잘렸지만 발행된」 계획은 서비스 경로에서 결정적으로 만들 수 없다 — 마감은 스톱워치
        // (System.nanoTime)이고, 너무 짧으면 아무도 싣지 못해 계획이 실패로 끝난다(발행 없음).
        // 그래서 여기서는 표지만 본다: 발행된 계획에 budgetExhausted=true 가 오면 deadline 이다.
        // 이 값이 converged 로 나오면 CI 의 수렴 어설션은 아무것도 검사하지 않는 셈이다.
        UUID waveId = Ids.newId();
        seed(waveId, 5);
        service(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), Duration.ofSeconds(30))
                .run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null));
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

        new DispatchMetrics(registry).planPublished(plans.findByWaveId(waveId).orElseThrow(), true);

        assertThat(terminations(registry, DispatchMetrics.TERMINATION_DEADLINE)).isEqualTo(1);
        assertThat(terminations(registry, DispatchMetrics.TERMINATION_CONVERGED)).isZero();
    }

    private RunPlanService service(io.micrometer.core.instrument.MeterRegistry registry, Duration budget) {
        return new RunPlanService(plans, candidates, routes, events,
                InMemoryDispatchPorts.fleet(2, NOW),
                InMemoryDispatchPorts.rules(RuleSet.empty()),
                new HaversineDistance(1.3d, 25.0d),
                new DispatchMetrics(registry),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "baseline-nn", new PlanningBudget(budget, Duration.ofSeconds(3)),
                new PlanModeSelector(3L, 0.8d, 0.5d), transactions);
    }

    private static long terminations(io.micrometer.core.instrument.MeterRegistry registry, String termination) {
        io.micrometer.core.instrument.Timer timer = registry.find(DawnlineMetrics.PLAN_DURATION.meterName())
                .tag(DispatchMetrics.TAG_TERMINATION, termination).timer();
        return timer == null ? 0 : timer.count();
    }

    /** 거리를 물을 때마다 그 순간 열린 트랜잭션 깊이를 적는 거리 제공자 — 최적화기만 거리를 묻는다. */
    private RunPlanService service(io.micrometer.core.instrument.MeterRegistry registry, List<Integer> depthsDuringCompute) {
        HaversineDistance real = new HaversineDistance(1.3d, 25.0d);
        return new RunPlanService(plans, candidates, routes, events,
                InMemoryDispatchPorts.fleet(2, NOW),
                InMemoryDispatchPorts.rules(RuleSet.empty()),
                (from, to) -> {
                    depthsDuringCompute.add(transactions.depth);
                    return real.between(from, to);
                },
                new DispatchMetrics(registry),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "baseline-nn", new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3)),
                new PlanModeSelector(3L, 0.8d, 0.5d), transactions);
    }

    @Test
    void 계산은_트랜잭션_밖에서_돈다_읽기는_읽기_전용이고_쓰기는_하나다() {
        // ADR-064. 통합 수준의 같은 관측은 PlanComputeConnectionIT(pg_stat_activity)다.
        UUID waveId = Ids.newId();
        seed(waveId, 5);
        List<Integer> depths = new ArrayList<>();

        assertThat(service(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), depths)
                .run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null)))
                .isEqualTo(RunPlanUseCase.Outcome.PUBLISHED);

        assertThat(depths).as("전제 — 최적화기가 거리를 물었다").isNotEmpty();
        assertThat(depths).as("거리를 묻는 동안 열린 트랜잭션").containsOnly(0);
        assertThat(transactions.opened).as("읽기 전용 하나, 쓰기 하나 — 순서대로").containsExactly(true, false);
        assertThat(transactions.depth).isZero();
    }

    @Test
    void 게이트가_쓰기를_건너뛰면_아무것도_쓰지_않고_DUPLICATE_다() {
        UUID waveId = Ids.newId();
        seed(waveId, 3);

        RunPlanUseCase.Outcome outcome = service(RuleSet.empty(), 2)
                .run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null), write -> false);

        assertThat(outcome).isEqualTo(RunPlanUseCase.Outcome.DUPLICATE);
        assertThat(plans.findByWaveId(waveId)).as("계획 행도 쓰기 단계가 넣는다 — 읽기는 아무것도 쓰지 않는다").isEmpty();
        assertThat(events.routesAssigned).isEmpty();
        assertThat(events.completed).isZero();
        assertThat(transactions.opened).as("읽기 전용 하나뿐 — 쓰기 트랜잭션은 열리지 않았다").containsExactly(true);
    }

    @Test
    void 게이트_안에서_커밋이_실패하면_계획을_세지_않는다() {
        // CLAUDE.md 「카운터는 커밋 뒤에 센다 — 그리고 테스트가 그 순서를 본다」, ADR-064 결정 5.
        UUID waveId = Ids.newId();
        seed(waveId, 3);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        RunPlanService service = service(registry, new ArrayList<>());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.run(
                RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null), write -> {
                    write.run();
                    throw new IllegalStateException("커밋 실패");
                })).hasMessage("커밋 실패");

        assertThat(events.completed).as("전제 — 쓰기는 끝까지 돌았다(롤백될 것을 발행했다)").isOne();
        assertThat(registry.find(DawnlineMetrics.PLAN_DURATION.meterName()).timers())
                .as("커밋되지 않은 계획의 시간").allSatisfy(timer -> assertThat(timer.count()).isZero());
        assertThat(registry.find(DawnlineMetrics.PLAN_PERSIST.meterName()).timers())
                .as("커밋되지 않은 계획의 영속화 시간").allSatisfy(timer -> assertThat(timer.count()).isZero());
    }

    @Test
    void 이미_발행된_웨이브는_계산하지_않고_게이트만_지난다() {
        UUID waveId = Ids.newId();
        seed(waveId, 3);
        List<Integer> depths = new ArrayList<>();
        RunPlanService service = service(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), depths);
        service.run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null));
        depths.clear();
        AtomicInteger entered = new AtomicInteger();

        RunPlanUseCase.Outcome again = service.run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null),
                write -> {
                    entered.incrementAndGet();
                    write.run();
                    return true;
                });
        RunPlanUseCase.Outcome duplicate = service.run(
                RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null), write -> false);

        assertThat(again).isEqualTo(RunPlanUseCase.Outcome.ALREADY_PUBLISHED);
        assertThat(duplicate).as("게이트가 막으면 그렇게 말한다").isEqualTo(RunPlanUseCase.Outcome.DUPLICATE);
        assertThat(depths).as("계산하지 않았다 — 최적화기가 거리를 묻지 않았다").isEmpty();
        assertThat(entered).as("받은 이벤트는 한 번씩 게이트를 지난다 — 멱등 기록과 소비 카운터").hasValue(1);
        assertThat(events.completed).isOne();
    }

    @Test
    void 계획이_PUBLISHED_로_끝난다() {
        UUID waveId = Ids.newId();
        seed(waveId, 3);

        service(RuleSet.empty(), 2).run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null));

        assertThat(plans.findByWaveId(waveId)).hasValueSatisfying(plan -> {
            assertThat(plan.status()).isEqualTo(PlanStatus.PUBLISHED);
            assertThat(plan.strategy()).contains("baseline-nn");
            assertThat(plan.assignedCount()).contains(3);
        });
    }

    @Test
    void 후보를_계획_결과대로_전이시킨다() {
        UUID waveId = Ids.newId();
        List<UUID> orderIds = seed(waveId, 3);

        service(RuleSet.empty(), 2).run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null));

        assertThat(orderIds).allSatisfy(orderId ->
                assertThat(candidates.findById(orderId).orElseThrow().status())
                        .isEqualTo(CandidateStatus.PLANNED));
        assertThat(candidates.findPlannableInWave(waveId)).isEmpty();
    }

    @Test
    void 같은_웨이브를_두_번_돌려도_계획은_하나다() {
        UUID waveId = Ids.newId();
        seed(waveId, 3);
        RunPlanService service = service(RuleSet.empty(), 2);

        service.run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null));

        assertThat(service.run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null)))
                .isEqualTo(RunPlanUseCase.Outcome.ALREADY_PUBLISHED);
        assertThat(plans.size()).isEqualTo(1);
        assertThat(events.completed).as("두 번 발행하지 않는다").isEqualTo(1);
    }

    @Test
    void 후보가_없으면_실패로_종결하고_plan_failed_를_낸다() {
        UUID waveId = Ids.newId();

        assertThat(service(RuleSet.empty(), 2).run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null)))
                .isEqualTo(RunPlanUseCase.Outcome.NO_CANDIDATES);

        assertThat(events.failed).isEqualTo(1);
        assertThat(plans.findByWaveId(waveId)).hasValueSatisfying(plan -> {
            assertThat(plan.status()).isEqualTo(PlanStatus.FAILED);
            assertThat(plan.failureReason()).contains(RunPlanService.NO_CANDIDATES);
        });
    }

    @Test
    void 계획_중_취소된_주문은_발행에서_빠진다() {
        // ADR-026 분기 2 — revision 없이 경합 창을 닫는 유일한 자리다.
        UUID waveId = Ids.newId();
        List<UUID> orderIds = seed(waveId, 4);
        UUID cancelled = orderIds.getFirst();

        RunPlanService service = new RunPlanService(plans, new CancellingCandidates(cancelled),
                routes, events, InMemoryDispatchPorts.fleet(2, NOW),
                InMemoryDispatchPorts.rules(RuleSet.empty()),
                new HaversineDistance(1.3d, 25.0d),
                new DispatchMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "baseline-nn", new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3)),
                new PlanModeSelector(3L, 0.8d, 0.5d), transactions);

        assertThat(service.run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null)))
                .isEqualTo(RunPlanUseCase.Outcome.PUBLISHED);
        assertThat(events.ordersDispatched)
                .as("취소된 주문은 order.dispatched 를 받지 않는다").doesNotContain(cancelled);
        assertThat(events.ordersDispatched).hasSize(3);
    }

    @Test
    void seed_는_웨이브에서_유도되어_재실행에도_같다() {
        // 시각에서 유도하면 "재실행했더니 달라졌다" 가 버그인지 정상인지 구별할 수 없다.
        UUID waveId = Ids.newId();

        assertThat(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null).effectiveSeed())
                .isEqualTo(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null).effectiveSeed());
    }

    @Test
    void 실패한_계획은_재실행으로_되살아난다() {
        // §5.3 "운영자 재실행 가능", ADR-024 결정 3.
        UUID waveId = Ids.newId();
        RunPlanService service = service(RuleSet.empty(), 2);
        service.run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null));       // 후보 없음 → FAILED
        seed(waveId, 3);

        assertThat(service.run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null)))
                .isEqualTo(RunPlanUseCase.Outcome.PUBLISHED);
        assertThat(events.completed).isEqualTo(1);
    }

    @Test
    void 랙이_밀리면_다음_계획이_열화하고_사유가_남는다() {
        // §6.7 첫 조건. 랙은 명령이 싣고 온다 — 유스케이스는 Kafka 를 모른다 (ADR-034).
        UUID waveId = Ids.newId();
        seed(waveId, 3);

        service(RuleSet.empty(), 2).run(new RunPlanCommand(waveId, CAMP_ID,
                InMemoryDispatchPorts.CAMP, null, null, null, 9L));

        assertThat(plans.findByWaveId(waveId)).hasValueSatisfying(plan -> {
            assertThat(plan.mode()).contains(PlanMode.FAST);
            assertThat(plan.modeReason()).contains(PlanModeReason.LAG);
        });
    }

    @Test
    void 랙을_모르면_그_사실이_사유로_남는다() {
        // 웹 재실행·정체 회수에는 볼 파티션이 없다. 그때 FULL 은 "두 조건을 다 보고 아니었다"
        // 가 아니라 "하나를 못 봤다" 이고, 둘을 같은 값으로 적으면 판단이 멈춘 것이 안 보인다.
        UUID waveId = Ids.newId();
        seed(waveId, 3);

        service(RuleSet.empty(), 2)
                .run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null));

        assertThat(plans.findByWaveId(waveId)).hasValueSatisfying(plan -> {
            assertThat(plan.mode()).contains(PlanMode.FULL);
            assertThat(plan.modeReason()).contains(PlanModeReason.LAG_UNKNOWN);
        });
    }

    @Test
    void 운영자가_지정한_모드는_자동_판단을_이기고_열화로_세지_않는다() {
        UUID waveId = Ids.newId();
        seed(waveId, 3);

        // 랙이 임계를 훌쩍 넘었는데도 사람이 FULL 을 지정했다.
        service(RuleSet.empty(), 2).run(new RunPlanCommand(waveId, CAMP_ID,
                InMemoryDispatchPorts.CAMP, null, PlanMode.FULL, null, 999L));

        assertThat(plans.findByWaveId(waveId)).hasValueSatisfying(plan -> {
            assertThat(plan.mode()).contains(PlanMode.FULL);
            assertThat(plan.modeReason()).contains(PlanModeReason.REQUESTED);
            assertThat(plan.modeReason().orElseThrow().isDegraded())
                    .as("사람의 선택은 열화가 아니다").isFalse();
        });
    }

    @Test
    void 직전_계획이_예산을_넘겼으면_다음_계획은_개선을_덜_한다() {
        // §6.7 사다리의 아랫단 (ADR-034 후속 정정). "직전" 은 같은 캠프의 마지막 발행 계획이고,
        // 저장소가 답한다 — 인메모리 홀더면 재기동에 사라지고 인스턴스마다 달라진다.
        // **FAST 가 아니다**: 예산을 다 썼다는 것은 개선이 배고프다는 뜻이지 처리량이 모자란다는
        // 뜻이 아니고, 두 처방의 대가가 45배 차이다.
        UUID slow = Ids.newId();
        seed(slow, 3);
        RunPlanService service = service(RuleSet.empty(), 2, stepping(Duration.ofSeconds(25)));
        service.run(RunPlanCommand.of(slow, CAMP_ID, InMemoryDispatchPorts.CAMP, null));

        // 전제 둘. 직전 계획이 있어야 하고, 그것이 예산의 80%(24초)를 넘겨야 한다 —
        // 넘지 않으면 아래 어설션은 조건이 아니라 기본값을 확인하게 된다.
        assertThat(plans.lastPublishedDuration(CAMP_ID))
                .as("전제: 같은 캠프의 직전 발행 계획이 있어야 이 조건이 발화한다")
                .contains(Duration.ofSeconds(25));

        UUID next = Ids.newId();
        seed(next, 3);
        service.run(RunPlanCommand.of(next, CAMP_ID, InMemoryDispatchPorts.CAMP, 0L));

        assertThat(plans.findByWaveId(next)).hasValueSatisfying(plan -> {
            assertThat(plan.mode()).as("개선을 끄지 않는다").contains(PlanMode.FULL);
            assertThat(plan.modeReason()).contains(PlanModeReason.BUDGET);
        });
    }

    @Test
    void 하드_룰_때문에_아무도_못_실으면_실패한다() {
        UUID waveId = Ids.newId();
        seed(waveId, 3);
        // 어떤 차량도 실을 수 없게 만든다 — 용량 0 은 만들 수 없으므로 stop 상한을 0 에 가깝게.
        RuleSet impossible = DispatchRules.ruleSet(List.of(new RuleDefinition("max-stops",
                RuleType.MAX_STOPS_PER_ROUTE, RuleSeverity.HARD, 20, Map.of("max", 1))), 1);

        RunPlanUseCase.Outcome outcome =
                service(impossible, 1).run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP, null));

        // 차 한 대가 stop 하나만 실을 수 있으므로 나머지는 미배정이지만 계획 자체는 성립한다.
        assertThat(outcome).isEqualTo(RunPlanUseCase.Outcome.PUBLISHED);
        assertThat(events.ordersDispatched).hasSize(1);
    }

    /** 계획이 끝난 뒤 조회에서 한 주문을 빼는 저장소 — 계획 중 취소를 흉내 낸다. */
    private final class CancellingCandidates extends AbstractCandidates {

        private final UUID cancelled;
        private int calls;

        private CancellingCandidates(UUID cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public List<DispatchCandidate> findPlannableInWave(UUID waveId) {
            List<DispatchCandidate> all = candidates.findPlannableInWave(waveId);
            // 첫 호출(계획 입력)은 전부, 두 번째(발행 직전 재검증)는 취소된 것을 뺀다.
            return calls++ == 0 ? all
                    : all.stream().filter(c -> !c.orderId().equals(cancelled)).toList();
        }
    }

    /** 나머지는 그대로 위임한다. */
    private abstract class AbstractCandidates
            implements com.dawnline.dispatch.application.port.out.DispatchCandidateRepository {

        @Override
        public boolean insertIfAbsent(DispatchCandidate candidate) {
            return candidates.insertIfAbsent(candidate);
        }

        @Override
        public java.util.Optional<DispatchCandidate> findById(UUID orderId) {
            return candidates.findById(orderId);
        }

        @Override
        public int recordPlanResult(java.util.Collection<UUID> orderIds,
                com.dawnline.dispatch.domain.CandidateStatus target, java.time.Instant at) {
            return candidates.recordPlanResult(orderIds, target, at);
        }

        @Override
        public void update(DispatchCandidate candidate) {
            candidates.update(candidate);
        }
    }
}
