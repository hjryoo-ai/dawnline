package com.dawnline.web.internal;

/**
 * 내부 토큰의 이름들 (DESIGN.md §10, ADR-055). 코어(검사)와 ops-api(싣기)가 같은 문자열을 쓴다.
 */
public final class InternalToken {

    /** 토큰을 싣는 요청 헤더. */
    public static final String HEADER = "X-Dawnline-Internal";

    /** OpenAPI 문서의 보안 스킴 이름 — 토큰 대상 오퍼레이션의 {@code security} 에 이 이름이 붙는다. */
    public static final String SECURITY_SCHEME = "internalToken";

    /**
     * 거부 카운터({@code DawnlineMetrics.INTERNAL_TOKEN_REJECTED}, Prometheus {@code dawnline_internal_token_rejected_total})의
     * 사유 태그 — 값은 {@code missing}·{@code mismatch}. 이름은 카탈로그에 있다(ADR-060). 정상 운영에서 0 이다.
     */
    public static final String REASON_TAG = "reason";

    /** 설정 접두어. */
    public static final String PROPERTY_PREFIX = "dawnline.web.internal-token";

    private InternalToken() {
    }
}
