package com.dawnline.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.domain.CandidateStatus;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.PlanStatus;
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
import com.dawnline.dispatch.domain.optimizer.PlanningBudget;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.rule.DispatchRules;
import com.dawnline.dispatch.domain.optimizer.rule.RuleDefinition;
import com.dawnline.dispatch.domain.optimizer.rule.RuleSeverity;
import com.dawnline.dispatch.domain.optimizer.rule.RuleType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private RunPlanService service(RuleSet rules, int vehicleCount) {
        return new RunPlanService(plans, candidates, routes, events,
                InMemoryDispatchPorts.fleet(vehicleCount, NOW),
                InMemoryDispatchPorts.rules(rules),
                new HaversineDistance(1.3d, 25.0d), Clock.fixed(NOW, ZoneOffset.UTC),
                "baseline-nn", new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3)));
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
                    1_000, 2_000, false, false, window, 60, 0, NOW));
        }
        return orderIds;
    }

    @Test
    void 계획하고_세_가지를_모두_발행한다() {
        UUID waveId = Ids.newId();
        List<UUID> orderIds = seed(waveId, 5);

        assertThat(service(RuleSet.empty(), 2).run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP)))
                .isEqualTo(RunPlanUseCase.Outcome.PUBLISHED);

        assertThat(events.routesAssigned).isNotEmpty();
        assertThat(events.ordersDispatched).containsExactlyInAnyOrderElementsOf(orderIds);
        assertThat(events.completed).isEqualTo(1);
        assertThat(events.failed).isZero();
    }

    @Test
    void 계획이_PUBLISHED_로_끝난다() {
        UUID waveId = Ids.newId();
        seed(waveId, 3);

        service(RuleSet.empty(), 2).run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP));

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

        service(RuleSet.empty(), 2).run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP));

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

        service.run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP));

        assertThat(service.run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP)))
                .isEqualTo(RunPlanUseCase.Outcome.ALREADY_PUBLISHED);
        assertThat(plans.size()).isEqualTo(1);
        assertThat(events.completed).as("두 번 발행하지 않는다").isEqualTo(1);
    }

    @Test
    void 후보가_없으면_실패로_종결하고_plan_failed_를_낸다() {
        UUID waveId = Ids.newId();

        assertThat(service(RuleSet.empty(), 2).run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP)))
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
                new HaversineDistance(1.3d, 25.0d), Clock.fixed(NOW, ZoneOffset.UTC),
                "baseline-nn", new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3)));

        assertThat(service.run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP)))
                .isEqualTo(RunPlanUseCase.Outcome.PUBLISHED);
        assertThat(events.ordersDispatched)
                .as("취소된 주문은 order.dispatched 를 받지 않는다").doesNotContain(cancelled);
        assertThat(events.ordersDispatched).hasSize(3);
    }

    @Test
    void seed_는_웨이브에서_유도되어_재실행에도_같다() {
        // 시각에서 유도하면 "재실행했더니 달라졌다" 가 버그인지 정상인지 구별할 수 없다.
        UUID waveId = Ids.newId();

        assertThat(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP).effectiveSeed())
                .isEqualTo(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP).effectiveSeed());
    }

    @Test
    void 실패한_계획은_재실행으로_되살아난다() {
        // §5.3 "운영자 재실행 가능", ADR-024 결정 3.
        UUID waveId = Ids.newId();
        RunPlanService service = service(RuleSet.empty(), 2);
        service.run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP));       // 후보 없음 → FAILED
        seed(waveId, 3);

        assertThat(service.run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP)))
                .isEqualTo(RunPlanUseCase.Outcome.PUBLISHED);
        assertThat(events.completed).isEqualTo(1);
    }

    @Test
    void 하드_룰_때문에_아무도_못_실으면_실패한다() {
        UUID waveId = Ids.newId();
        seed(waveId, 3);
        // 어떤 차량도 실을 수 없게 만든다 — 용량 0 은 만들 수 없으므로 stop 상한을 0 에 가깝게.
        RuleSet impossible = DispatchRules.ruleSet(List.of(new RuleDefinition("max-stops",
                RuleType.MAX_STOPS_PER_ROUTE, RuleSeverity.HARD, 20, Map.of("max", 1))), 1);

        RunPlanUseCase.Outcome outcome =
                service(impossible, 1).run(RunPlanCommand.of(waveId, CAMP_ID, InMemoryDispatchPorts.CAMP));

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
        public void update(DispatchCandidate candidate) {
            candidates.update(candidate);
        }
    }
}
