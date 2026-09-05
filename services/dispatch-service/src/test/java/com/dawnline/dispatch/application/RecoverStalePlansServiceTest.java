package com.dawnline.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Ids;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.domain.PlanMode;
import com.dawnline.dispatch.domain.PlanStatus;
import com.dawnline.dispatch.domain.RoutePlan;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 정체 회수 (§5.3).
 *
 * <p>이 스케줄러가 없으면 계획 중 죽은 인스턴스가 남긴 {@code PLANNING} 계획은 영원히 멈춘다 —
 * {@code wave_id} UNIQUE 라 다시 시도할 수도 없다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RecoverStalePlansServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-06T01:00:00Z");
    private static final Duration STALE_AFTER = Duration.ofMinutes(10);

    private final InMemoryDispatchPorts.Plans plans = new InMemoryDispatchPorts.Plans();
    private final List<UUID> reran = new ArrayList<>();

    /** 트랜잭션 없이 콜백만 실행한다 — 회수 로직만 보는 테스트다. */
    private static final PlatformTransactionManager NO_TX = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    };

    private final RunPlanUseCase runPlan = command -> {
        reran.add(command.waveId());
        return RunPlanUseCase.Outcome.PUBLISHED;
    };

    private RecoverStalePlansService service() {
        return new RecoverStalePlansService(plans, runPlan, NO_TX,
                Clock.fixed(NOW, ZoneOffset.UTC), STALE_AFTER, 20);
    }

    private RoutePlan planning(Instant startedAt) {
        RoutePlan plan = RoutePlan.request(Ids.newId(), Ids.newId(), Ids.newId());
        plans.insertIfAbsent(plan);
        plan.begin("baseline-nn", PlanMode.FULL, 1L, 1, startedAt);
        plans.update(plan);
        return plan;
    }

    @Test
    void 정체된_계획을_되돌리고_다시_돌린다() {
        RoutePlan stale = planning(NOW.minus(Duration.ofMinutes(11)));

        assertThat(service().recover()).isEqualTo(1);
        assertThat(reran).containsExactly(stale.waveId());
    }

    @Test
    void 아직_돌고_있는_계획은_건드리지_않는다() {
        // 경계 안쪽이다. 이것을 회수하면 살아 있는 계획을 죽이고 두 번 돌린다.
        planning(NOW.minus(Duration.ofMinutes(9)));

        assertThat(service().recover()).isZero();
        assertThat(reran).isEmpty();
    }

    @Test
    void 경계_직후는_회수한다() {
        planning(NOW.minus(Duration.ofMinutes(10)).minusSeconds(1));

        assertThat(service().recover()).isEqualTo(1);
    }

    @Test
    void 발행된_계획은_회수_대상이_아니다() {
        RoutePlan published = planning(NOW.minus(Duration.ofHours(1)));
        published.complete(com.dawnline.common.Money.ZERO, 1, 0, 10, NOW);
        published.publish(NOW);
        plans.update(published);

        assertThat(service().recover()).isZero();
    }

    @Test
    void 회수할_것이_없으면_아무것도_하지_않는다() {
        assertThat(service().recover()).isZero();
        assertThat(reran).isEmpty();
    }

    @Test
    void 여러_건을_모두_되돌린다() {
        planning(NOW.minus(Duration.ofMinutes(30)));
        planning(NOW.minus(Duration.ofMinutes(20)));

        assertThat(service().recover()).isEqualTo(2);
        assertThat(reran).hasSize(2);
    }

    @Test
    void 정체_판정_시간은_양수여야_한다() {
        assertThatThrownBy(() -> new RecoverStalePlansService(plans, runPlan, NO_TX,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 되돌린_계획은_다시_시작할_수_있는_상태다() {
        RoutePlan stale = planning(NOW.minus(Duration.ofMinutes(11)));

        service().recover();

        assertThat(plans.findById(stale.id())).hasValueSatisfying(plan ->
                assertThat(plan.status()).isIn(PlanStatus.REQUESTED, PlanStatus.PUBLISHED));
    }
}
