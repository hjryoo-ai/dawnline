package com.dawnline.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.dispatch.application.port.in.ReplanRouteUseCase;
import com.dawnline.dispatch.domain.PlanMode;
import com.dawnline.dispatch.domain.PlanModeReason;
import com.dawnline.observability.DawnlineMetrics;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * dispatch 가 내는 라벨 값은 카탈로그(DawnlineMetrics)의 닫힌 라벨 값 목록과 그 값을 만드는 enum 을 대조한다 (ADR-060 결정 2).
 *
 * <p>닫힌 라벨에 목록 밖의 값이 오면 등록 헬퍼가 실패한다 — 운영에서는 그 유스케이스가 예외를 낸다. enum 에 값이 느는
 * 날 그 실패가 운영의 첫 등록이 아니라 여기서 나야 한다.
 */
class MetricLabelValuesTest {

    @Test
    void 계획_mode_는_PlanMode_전부다() {
        assertThat(DawnlineMetrics.PLAN_DURATION.label("mode").values())
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(PlanMode.values()).map(Enum::name).toList());
    }

    @Test
    void 계획_termination_은_두_끝이다() {
        assertThat(DawnlineMetrics.PLAN_DURATION.label(DispatchMetrics.TAG_TERMINATION).values())
                .containsExactlyInAnyOrder(DispatchMetrics.TERMINATION_CONVERGED, DispatchMetrics.TERMINATION_DEADLINE);
    }

    @Test
    void 열화_reason_은_자동_열화_사유_전부다() {
        assertThat(DawnlineMetrics.PLAN_DEGRADED.label("reason").values()).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(PlanModeReason.values()).filter(PlanModeReason::isDegraded).map(Enum::name).toList());
    }

    @Test
    void 재계획_outcome_은_다섯_갈래_전부다() {
        assertThat(DawnlineMetrics.REPLAN.label(DispatchMetrics.TAG_OUTCOME).values()).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ReplanRouteUseCase.Outcome.values()).map(ReplanRouteUseCase.Outcome::label).toList());
    }
}
