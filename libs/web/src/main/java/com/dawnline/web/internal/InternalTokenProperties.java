package com.dawnline.web.internal;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 내부 토큰 설정 (DESIGN.md §10, ADR-055 결정 2·4).
 *
 * <p><strong>없거나 짧으면 기동하지 않는다</strong> — ops-api JWT 시크릿({@code OpsJwtProperties})과 같은 규칙이다.
 * 빈 토큰으로 뜬 코어는 운영자 쓰기를 전부 거부하거나(조용히 막힘), 비교를 잘못 쓰면 전부 받는다(조용히 열림).
 * 검사를 끈 서비스({@code enforce=false}, ops-api)도 값은 검증한다 — ops-api 는 이 값을 코어 호출에 싣는다.
 *
 * @param enforce 이 서비스의 쓰기에 토큰을 요구하는가. 코어는 {@code true}(기본), ops-api 는 {@code false}
 *                (자기 쓰기는 JWT·역할·감사가 지킨다)
 * @param secret  {@code DAWNLINE_INTERNAL_TOKEN} — 저장소에 두지 않는다. {@code make env} 가 무작위로 채운다
 */
@ConfigurationProperties(InternalToken.PROPERTY_PREFIX)
public record InternalTokenProperties(@DefaultValue("true") boolean enforce, @Nullable String secret) {

    /** 최소 길이(바이트). {@code openssl rand -hex 32} 는 64바이트다. */
    public static final int MIN_SECRET_BYTES = 32;

    public InternalTokenProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("DAWNLINE_INTERNAL_TOKEN 이 없다 — 코어와 ops-api 는 내부 토큰 없이 뜨지 않는다 "
                    + "(DESIGN.md §10, `make env` 가 만든다)");
        }
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException("DAWNLINE_INTERNAL_TOKEN 이 " + bytes + "바이트다 — "
                    + MIN_SECRET_BYTES + "바이트 이상이어야 한다");
        }
    }

    /** @return 검증된 토큰의 바이트 */
    public byte[] secretBytes() {
        return Objects.requireNonNull(secret).getBytes(StandardCharsets.UTF_8);
    }
}
