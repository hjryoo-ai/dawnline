package com.dawnline.benchmark;

import com.dawnline.common.Money;
import com.dawnline.dispatch.domain.optimizer.PlanMetrics;
import java.util.Objects;

/**
 * 한 회차의 측정 결과.
 *
 * @param metrics    계획 지표
 * @param totalCost  총비용. §6.9 회귀 게이트가 보는 값이다
 * @param durationMs 이 회차의 실제 계획 시간(ms). 벽시계다
 */
public record RunOutcome(PlanMetrics metrics, Money totalCost, long durationMs) {

    public RunOutcome {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(totalCost, "totalCost");
    }
}
