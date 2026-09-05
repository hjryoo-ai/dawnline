package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.error.ValidationException;
import java.util.Objects;

/**
 * 하드 룰 판정 결과 (DESIGN.md §6.3).
 *
 * <p>불가 판정은 <strong>사유를 반드시 들고 있다</strong> — 이 값이 그대로 {@link Explanation} 이
 * 되어 운영자의 "왜 이 주문이 미배정인가" 에 답하기 때문이다. 사유 없는 거절은 §6.3 이 룰을
 * 데이터로 둔 이유를 무너뜨린다.
 *
 * @param feasible 실을 수 있는가
 * @param ruleName 위반한 룰 이름. 통과면 {@code null}
 * @param reason   사람이 읽을 사유. 통과면 {@code null}
 */
public record Feasibility(boolean feasible, String ruleName, String reason) {

    private static final Feasibility OK = new Feasibility(true, null, null);

    public Feasibility {
        if (feasible && (ruleName != null || reason != null)) {
            throw new ValidationException("통과 판정에는 위반 사유가 없어야 합니다",
                    java.util.Map.of("ruleName", String.valueOf(ruleName)));
        }
        if (!feasible) {
            Objects.requireNonNull(ruleName, "ruleName");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** 통과. */
    public static Feasibility ok() {
        return OK;
    }

    /**
     * 불가.
     *
     * @param ruleName 위반한 룰 이름
     * @param reason   사유
     */
    public static Feasibility violated(String ruleName, String reason) {
        return new Feasibility(false, ruleName, reason);
    }
}
