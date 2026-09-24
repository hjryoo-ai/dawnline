package com.dawnline.ops;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * IT 가 쓰는 토큰 — {@code make token} 과 같은 다섯 클레임 (DESIGN.md §5.5 「인증」).
 *
 * <p>시각은 벽시계다: 운영 코드의 검증기가 주입된 시계로 만료를 보지만, IT 의 시계는 벽시계라 둘이 같다.
 */
final class OpsTokens {

    private OpsTokens() {
    }

    /**
     * @param role   역할 하나
     * @param actor  {@code sub}
     * @param secret HS256 시크릿
     * @param ttl    만료까지 — 음수면 이미 만료
     * @return 서명된 토큰
     */
    static String token(String role, String actor, String secret, Duration ttl) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("dawnline-ops-token")
                .subject(actor)
                .claim("roles", List.of(role))
                .issueTime(Date.from(now.minusSeconds(60)))
                .expirationTime(Date.from(now.plus(ttl)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    /** @return {@link OpsIntegrationTestBase#JWT_SECRET} 로 서명한 한 시간짜리 토큰 */
    static String token(String role, String actor) {
        return token(role, actor, OpsIntegrationTestBase.JWT_SECRET, Duration.ofHours(1));
    }
}
