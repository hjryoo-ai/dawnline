package com.dawnline.dispatch.domain.optimizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Ids;
import com.dawnline.common.error.ValidationException;
import java.util.Map;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ExplanationTest {

    private final OrderId order = OrderId.of(Ids.newId());
    private final VehicleId vehicle = VehicleId.of(Ids.newId());

    @Test
    void 배정_설명은_차량과_한계비용을_남긴다() {
        Explanation explanation = Explanation.assigned(order, vehicle, 1_840L);

        assertThat(explanation.outcome()).isEqualTo(Explanation.Outcome.ASSIGNED);
        assertThat(explanation.vehicle()).isEqualTo(vehicle);
        assertThat(explanation.detail()).containsEntry("marginalCostKrw", 1_840L);
    }

    @Test
    void 미배정_설명은_판정_사유를_그대로_옮긴다() {
        // §6.3 이 룰을 데이터로 둔 이유가 이것이다 — 사유를 볼 수 없으면 룰을 바꿀 근거도 없다.
        Feasibility violated = Feasibility.violated("cold-chain", "no cold vehicle with remaining capacity");

        Explanation explanation = Explanation.unassigned(order, violated, 3);

        assertThat(explanation.outcome()).isEqualTo(Explanation.Outcome.UNASSIGNED);
        assertThat(explanation.ruleName()).isEqualTo("cold-chain");
        assertThat(explanation.vehicle()).isNull();
        assertThat(explanation.detail())
                .containsEntry("reason", "no cold vehicle with remaining capacity")
                .containsEntry("triedVehicles", 3);
    }

    @Test
    void 통과_판정으로는_미배정_설명을_만들_수_없다() {
        assertThatThrownBy(() -> Explanation.unassigned(order, Feasibility.ok(), 1))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 배정_설명에_차량이_없으면_거부한다() {
        assertThatThrownBy(() -> new Explanation(order, Explanation.Outcome.ASSIGNED, null, null, Map.of()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void JSONB_에_넣을_수_없는_값은_거부한다() {
        // plan_explanations.detail 은 JSONB 다. 임의 객체를 담으면 저장 시점에야 터진다.
        assertThatThrownBy(() -> new Explanation(order, Explanation.Outcome.UNASSIGNED, "r", null,
                Map.of("at", java.time.Instant.EPOCH)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 문자열_숫자_불리언은_담을_수_있다() {
        Explanation explanation = new Explanation(order, Explanation.Outcome.UNASSIGNED, "r", null,
                Map.of("reason", "x", "tried", 3, "degraded", true));

        assertThat(explanation.detail()).hasSize(3);
    }
}
