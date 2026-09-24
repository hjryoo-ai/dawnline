package com.dawnline.messaging.outbox;

import com.dawnline.common.error.ErrorCode;

/**
 * outbox 운영 경로의 오류 코드 (DESIGN.md §4.6 「격리 조회·재큐 엔드포인트」).
 *
 * <p>{@code CommonErrorCode} 에 없는 것만 둔다 — 클라이언트가 코드만 보고 다음 행동을 정할 수 있어야 의미가 있다.
 */
public enum OutboxErrorCode implements ErrorCode {

    /**
     * 재큐하려는 행이 격리돼 있지 않다.
     *
     * <p>{@code conflict} 와 나누는 이유: 이 409 는 <strong>감사 {@code UNKNOWN} 을 닫는 근거</strong>다
     * (ADR-015 후속 정정 결정 3). 응답을 못 받은 재큐를 다시 누른 사람은 이 코드와 함께 온 {@code currentState}
     * 로 앞의 요청이 적용됐는지 읽는다 — ADR-054 의 {@code wave-not-open} 과 같은 형태다.
     */
    NOT_QUARANTINED("not-quarantined", 409, "격리된 outbox 행이 아닙니다");

    private final String code;
    private final int status;
    private final String title;

    OutboxErrorCode(String code, int status, String title) {
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
