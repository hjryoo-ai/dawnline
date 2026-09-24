package com.dawnline.web.internal;

import com.dawnline.common.error.DomainException;

/**
 * 내부 토큰 검사가 거부했다 (ADR-055 결정 2).
 *
 * <p>인터셉터가 응답을 직접 쓰지 않고 이것을 던진다 — 401 도 다른 오류와 같은 문(서비스의
 * {@code ProblemDetailsAdviceSupport} 하위 어드바이스)을 지나 Problem Details 가 된다. 직접 쓰면 「4xx 본문은
 * Problem Details」라는 오류 본문 계약에 예외가 하나 생긴다.
 */
public final class InternalTokenRejectedException extends DomainException {

    private static final long serialVersionUID = 1L;

    /** 헤더가 없거나 다르다. 어느 쪽인지는 싣지 않는다. */
    public InternalTokenRejectedException() {
        super(InternalTokenErrorCode.INTERNAL_TOKEN_REQUIRED,
                "운영자 쓰기 커맨드에는 내부 토큰이 필요합니다. ops-api 를 거치세요");
    }
}
