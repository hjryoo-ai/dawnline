package com.dawnline.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Ids;
import com.dawnline.common.Money;
import com.dawnline.common.error.IllegalStateTransitionException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RoutePlanTest {

    private static final Instant NOW = Instant.parse("2026-09-06T01:00:00Z");

    private static RoutePlan requested() {
        return RoutePlan.request(Ids.newId(), Ids.newId(), Ids.newId(), com.dawnline.common.GeoPoint.of(37.5663, 126.9779));
    }

    @Test
    void 요청된_계획은_아직_아무것도_모른다() {
        RoutePlan plan = requested();

        assertThat(plan.status()).isEqualTo(PlanStatus.REQUESTED);
        assertThat(plan.strategy()).isEmpty();
        assertThat(plan.totalCost()).isEmpty();
    }

    @Test
    void 시작하면_전략과_seed_와_룰_버전을_고정한다() {
        // 진행 중 계획은 시작 시점 스냅샷을 쓴다 (§6.3) — 룰이 바뀌어도 이 계획은 그대로다.
        RoutePlan plan = requested();

        plan.begin("baseline-nn", PlanMode.FULL, 42L, 7, NOW);

        assertThat(plan.status()).isEqualTo(PlanStatus.PLANNING);
        assertThat(plan.strategy()).contains("baseline-nn");
        assertThat(plan.seed()).contains(42L);
        assertThat(plan.ruleVersion()).contains(7);
        assertThat(plan.startedAt()).contains(NOW);
    }

    @Test
    void 완료와_발행이_나뉘어_있다() {
        // 결과를 담는 것과 이벤트를 낸 것은 다른 사실이다.
        RoutePlan plan = requested();
        plan.begin("baseline-nn", PlanMode.FULL, 1L, 1, NOW);

        plan.complete(Money.krw(1_500_000), 480, 20, 674, NOW.plusSeconds(1));
        assertThat(plan.status()).isEqualTo(PlanStatus.PLANNED);
        assertThat(plan.totalCost()).contains(Money.krw(1_500_000));
        assertThat(plan.assignedCount()).contains(480);
        assertThat(plan.planDurationMs()).contains(674);

        plan.publish(NOW.plusSeconds(2));
        assertThat(plan.status()).isEqualTo(PlanStatus.PUBLISHED);
    }

    @Test
    void 실패한_계획은_재실행으로_되살아난다() {
        // ADR-024 결정 3 — 재실행이 성공하면 plan.completed 가 다시 나가 웨이브를 되돌린다.
        RoutePlan plan = requested();
        plan.begin("baseline-nn", PlanMode.FULL, 1L, 1, NOW);
        plan.fail("TIMEOUT", NOW.plusSeconds(1));

        assertThat(plan.status()).isEqualTo(PlanStatus.FAILED);
        assertThat(plan.failureReason()).contains("TIMEOUT");

        plan.requeue(NOW.plusSeconds(2));
        plan.begin("baseline-nn", PlanMode.FULL, 1L, 1, NOW.plusSeconds(3));

        assertThat(plan.status()).isEqualTo(PlanStatus.PLANNING);
        assertThat(plan.failureReason()).as("재시작하면 지난 실패 사유는 지운다").isEmpty();
    }

    @Test
    void 정체된_계획을_되돌린다() {
        RoutePlan plan = requested();
        plan.begin("baseline-nn", PlanMode.FULL, 1L, 1, NOW);

        plan.requeue(NOW.plusSeconds(600));

        assertThat(plan.status()).isEqualTo(PlanStatus.REQUESTED);
        assertThat(plan.startedAt()).as("다시 시작할 것이므로 시작 시각을 지운다").isEmpty();
    }

    @Test
    void 계획하지_않고_완료할_수_없다() {
        assertThatThrownBy(() -> requested().complete(Money.ZERO, 0, 0, 0, NOW))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void 음수_수치로_완료할_수_없다() {
        RoutePlan plan = requested();
        plan.begin("baseline-nn", PlanMode.FULL, 1L, 1, NOW);

        assertThatThrownBy(() -> plan.complete(Money.ZERO, -1, 0, 0, NOW))
                .isInstanceOf(com.dawnline.common.error.ValidationException.class);
    }
}
