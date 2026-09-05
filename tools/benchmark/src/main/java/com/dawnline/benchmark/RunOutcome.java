package com.dawnline.benchmark;

import com.dawnline.common.Money;
import com.dawnline.dispatch.domain.optimizer.PlanMetrics;
import java.util.Map;
import java.util.Objects;

/**
 * 한 회차의 측정 결과.
 *
 * @param metrics            계획 지표
 * @param totalCost          총비용. §6.9 회귀 게이트가 보는 값이다
 * @param durationMs         이 회차의 실제 계획 시간(ms). 벽시계다
 * @param unassignedReasons  미배정 사유별 주문 수. 수만 세면 "왜" 가 사라지고, 그러면 §6.3 이
 *                           설명을 요구한 이유가 리포트에서 없어진다
 */
public record RunOutcome(PlanMetrics metrics, Money totalCost, long durationMs,
        Map<String, Long> unassignedReasons) {

    public RunOutcome {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(totalCost, "totalCost");
        unassignedReasons = Map.copyOf(Objects.requireNonNull(unassignedReasons, "unassignedReasons"));
    }

    /** 사유가 필요 없는 자리(테스트)에서 쓴다. */
    public RunOutcome(PlanMetrics metrics, Money totalCost, long durationMs) {
        this(metrics, totalCost, durationMs, Map.of());
    }

    /** 가장 많은 사유. 미배정이 없으면 빈 문자열. */
    public String dominantReason() {
        return unassignedReasons.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> "%s (%d)".formatted(entry.getKey(), entry.getValue()))
                .orElse("—");
    }
}
