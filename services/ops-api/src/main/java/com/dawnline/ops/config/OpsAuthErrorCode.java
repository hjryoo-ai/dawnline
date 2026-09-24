package com.dawnline.ops.config;

import com.dawnline.common.error.ErrorCode;

/**
 * ops-api 의 인증·인가 오류 코드 (DESIGN.md §5.5 「인증」 · §10).
 *
 * <p>보안 필터에서 나는 오류도 다른 오류와 같은 문(서비스의 단일 어드바이스)을 지나 Problem Details 가 된다 — 필터가
 * 응답을 직접 쓰면 「4xx 본문은 Problem Details」라는 오류 본문 계약에 예외가 생기고, 문서(§11)는 그 예외를 말할
 * 방법이 없다. 둘을 나누는 이유는 화면이 다른 일을 하기 때문이다: 401 은 토큰을 다시 넣으라는 뜻이고 403 은 그
 * 역할로는 안 된다는 뜻이다.
 */
public enum OpsAuthErrorCode implements ErrorCode {

    /** 토큰이 없거나, 서명·발급자·만료가 맞지 않는다. 어느 쪽인지는 본문이 말하지 않는다. */
    UNAUTHENTICATED("unauthenticated", 401, "인증이 필요합니다"),

    /** 토큰은 유효한데 역할이 모자란다 — 뷰어가 커맨드를 불렀다. */
    FORBIDDEN("forbidden", 403, "권한이 없습니다");

    private final String code;
    private final int status;
    private final String title;

    OpsAuthErrorCode(String code, int status, String title) {
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
