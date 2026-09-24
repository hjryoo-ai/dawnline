package com.dawnline.ops.config;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ops-api 의 JWT 검증 시크릿 (DESIGN.md §5.5 · §10, ADR-052 결정 2).
 *
 * <p>발급은 {@code make token} 이 같은 시크릿으로 한다. ops-api 는 검증만 한다.
 *
 * <p><strong>없거나 짧으면 기동하지 않는다.</strong> HS256 의 키는 256비트(32바이트) 이상이어야 하고, 빈
 * 시크릿으로 뜬 서버는 누구의 서명이든 받아들이거나 전부 거부한다 — 어느 쪽이든 「열린 채로」 또는 「조용히
 * 막힌 채로」 뜬 것이다. 뜨지 않는 것이 둘보다 낫다.
 *
 * @param secret {@code DAWNLINE_OPS_JWT_SECRET} — 저장소에 두지 않는다(CLAUDE.md)
 */
@ConfigurationProperties("dawnline.ops.jwt")
public record OpsJwtProperties(@Nullable String secret) {

    /** HS256 키의 최소 길이(바이트). */
    static final int MIN_SECRET_BYTES = 32;

    public OpsJwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "DAWNLINE_OPS_JWT_SECRET 가 없다 — ops-api 는 시크릿 없이 뜨지 않는다 (DESIGN.md §5.5, `make env` 가 만든다)");
        }
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException("DAWNLINE_OPS_JWT_SECRET 가 " + bytes + "바이트다 — HS256 은 "
                    + MIN_SECRET_BYTES + "바이트(256비트) 이상이어야 한다");
        }
    }

    /** @return 검증된 시크릿의 바이트 */
    byte[] secretBytes() {
        return Objects.requireNonNull(secret).getBytes(StandardCharsets.UTF_8);
    }
}
