package com.dawnline.common.error;

/**
 * 모든 서비스가 공유하는 기본 오류 코드.
 *
 * <p>서비스 고유 오류는 각 서비스에서 {@link ErrorCode} 를 구현하는 enum 을 따로 정의한다.
 * 여기에는 어느 서비스에서나 의미가 같은 것만 둔다.
 */
public enum CommonErrorCode implements ErrorCode {

    /** 요청 값 자체가 유효하지 않다(형식·범위 위반). */
    VALIDATION_FAILED("validation-failed", 400, "요청 값이 유효하지 않습니다"),

    /** 형식은 맞지만 의미적으로 처리할 수 없다. 예: 같은 멱등 키에 다른 본문(DESIGN.md §5.1). */
    UNPROCESSABLE_REQUEST("unprocessable-request", 422, "요청을 처리할 수 없습니다"),

    /** 대상 리소스가 없다. */
    NOT_FOUND("not-found", 404, "리소스를 찾을 수 없습니다"),

    /** 현재 상태와 충돌한다. 예: 처리 중인 멱등 키 재요청. */
    CONFLICT("conflict", 409, "현재 상태와 충돌합니다"),

    /** 애그리거트 상태 머신이 허용하지 않는 전이(CLAUDE.md 불변규칙 6). */
    ILLEGAL_STATE_TRANSITION("illegal-state-transition", 409, "허용되지 않은 상태 전이입니다"),

    /** 의존 구성요소가 일시적으로 불가해 요청을 받을 수 없다(백프레셔·열화). */
    UNAVAILABLE("unavailable", 503, "일시적으로 요청을 처리할 수 없습니다");

    private final String code;
    private final int status;
    private final String title;

    CommonErrorCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
