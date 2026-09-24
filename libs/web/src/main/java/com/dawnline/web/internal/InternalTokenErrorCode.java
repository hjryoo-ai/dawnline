package com.dawnline.web.internal;

import com.dawnline.common.error.ErrorCode;

/**
 * 내부 토큰의 오류 코드 (ADR-055 결정 2).
 */
public enum InternalTokenErrorCode implements ErrorCode {

    /**
     * 운영자 쓰기에 내부 토큰이 없거나 다르다. 둘을 나누지 않는다 — 응답은 어느 쪽이 틀렸는지 말하지 않고
     * 로그만 말한다.
     */
    INTERNAL_TOKEN_REQUIRED("internal-token-required", 401, "내부 토큰이 필요합니다");

    private final String code;
    private final int status;
    private final String title;

    InternalTokenErrorCode(String code, int status, String title) {
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
