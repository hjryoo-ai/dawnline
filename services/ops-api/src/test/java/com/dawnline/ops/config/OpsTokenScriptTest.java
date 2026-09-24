package com.dawnline.ops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * {@code make token} 이 찍는 토큰을 ops-api 의 검증기가 받는가 — 발급과 검증은 두 곳에 있고, 둘을 잇는
 * 것은 클레임 이름 다섯과 시크릿의 바이트 해석뿐이다 (DESIGN.md §5.5, ADR-052 결정 2).
 *
 * <p>스크립트를 <strong>실제로 실행한다</strong>(bash + openssl). 같은 알고리즘을 자바로 다시 적어 검사하면
 * 서로를 비추는 두 목록 중 하나를 검사가 새로 만드는 셈이다. 검증기는 {@link SecurityConfig#decoder} —
 * 운영의 빈과 같은 메서드다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("OpsTokenScriptTest — make token 과 ops-api 의 검증기")
class OpsTokenScriptTest {

    private static final String SECRET = "test-only-secret-0123456789abcdef-0123456789";
    private static final Path SCRIPT = locate("tools/ops-token/ops-token.sh");

    @Test
    void 스크립트가_찍은_토큰을_검증기가_받고_클레임이_감사에_쓸_모양이다() throws Exception {
        Instant before = Instant.now().minusSeconds(5);
        String token = mint(SECRET, "OPS_OPERATOR", "kim.ops");

        Jwt jwt = decoder(SECRET, Clock.systemUTC()).decode(token);

        assertThat(jwt.getSubject()).isEqualTo("kim.ops");
        assertThat(jwt.getClaimAsStringList(SecurityConfig.ROLES_CLAIM)).containsExactly("OPS_OPERATOR");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(SecurityConfig.ISSUER);
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
                .as("만료 12시간 — 데모 토큰이 남았을 때의 반경").isEqualTo(Duration.ofHours(12));
        assertThat(jwt.getIssuedAt()).isAfter(before);
    }

    @Test
    void 만료가_지난_토큰은_받지_않는다() throws Exception {
        String token = mint(SECRET, "OPS_VIEWER", "kim.ops");
        Clock later = Clock.fixed(Instant.now().plus(Duration.ofHours(12)).plus(Duration.ofMinutes(2)), ZoneOffset.UTC);

        assertThatThrownBy(() -> decoder(SECRET, later).decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void 다른_시크릿으로_찍은_토큰은_받지_않는다() throws Exception {
        String token = mint("another-secret-0123456789abcdef-0123456789", "ADMIN", "kim.ops");

        assertThatThrownBy(() -> decoder(SECRET, Clock.systemUTC()).decode(token))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void 스크립트는_없는_역할과_짧은_시크릿과_12시간_넘는_만료를_거부한다() throws Exception {
        assertThat(run(SECRET, List.of("BOSS"), Map.of()).exitCode()).isEqualTo(2);
        assertThat(run("short", List.of("OPS_VIEWER"), Map.of()).exitCode()).isEqualTo(2);
        assertThat(run(SECRET, List.of("OPS_VIEWER", "kim\"}"), Map.of()).exitCode())
                .as("actor 는 JSON 에 그대로 들어간다 — 따옴표를 받지 않는다").isEqualTo(2);
        assertThat(run(SECRET, List.of("OPS_VIEWER"), Map.of("OPS_TOKEN_TTL_SECONDS", "43201")).exitCode())
                .as("만료는 줄일 수만 있다").isEqualTo(2);
    }

    private static JwtDecoder decoder(String secret, Clock clock) {
        return SecurityConfig.decoder(new OpsJwtProperties(secret), clock);
    }

    private static String mint(String secret, String role, String actor) throws Exception {
        Result result = run(secret, List.of(role, actor), Map.of());
        assertThat(result.exitCode()).as(result.output()).isZero();
        return result.output().strip();
    }

    private static Result run(String secret, List<String> args, Map<String, String> env) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", SCRIPT.toString()));
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("DAWNLINE_OPS_JWT_SECRET", secret);
        builder.environment().putAll(env);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        return new Result(process.exitValue(), output);
    }

    private static Path locate(String relative) {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(relative + " 를 찾지 못했다");
    }

    private record Result(int exitCode, String output) {
    }
}
