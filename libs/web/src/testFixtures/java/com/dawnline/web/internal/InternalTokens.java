package com.dawnline.web.internal;

/**
 * 테스트가 쓰는 내부 토큰 (ADR-055). IT 기반이 {@code dawnline.web.internal-token.secret} 에 넣고, 보호 대상 쓰기를
 * 부르는 테스트가 {@value InternalToken#HEADER} 에 싣는다.
 */
public final class InternalTokens {

    /** 테스트 전용 값. 32바이트 이상이다. */
    public static final String TEST_TOKEN = "integration-test-only-internal-token-0123456789";

    /** 설정 키. */
    public static final String SECRET_PROPERTY = InternalToken.PROPERTY_PREFIX + ".secret";

    private InternalTokens() {
    }
}
