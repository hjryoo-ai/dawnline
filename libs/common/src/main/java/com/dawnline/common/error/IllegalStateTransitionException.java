package com.dawnline.common.error;

import java.io.Serial;
import java.util.Map;

/**
 * 애그리거트 상태 머신이 허용하지 않는 전이를 시도했다. HTTP 409.
 *
 * <p>CLAUDE.md 불변규칙 6: 상태는 세터가 아니라 애그리거트 메서드로만 바꾸고,
 * 잘못된 전이는 이 예외로 거부한다.
 */
public class IllegalStateTransitionException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param aggregate    애그리거트 이름 (예: {@code "Order"})
     * @param currentState 현재 상태
     * @param attempted    시도한 상태 또는 동작
     */
    public IllegalStateTransitionException(String aggregate, Object currentState, Object attempted) {
        super(
                CommonErrorCode.ILLEGAL_STATE_TRANSITION,
                aggregate + ": " + currentState + " → " + attempted + " 전이는 허용되지 않습니다",
                Map.of(
                        "aggregate", aggregate,
                        "currentState", String.valueOf(currentState),
                        "attempted", String.valueOf(attempted)));
    }
}
