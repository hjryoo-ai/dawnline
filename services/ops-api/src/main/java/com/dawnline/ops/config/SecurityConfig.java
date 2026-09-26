package com.dawnline.ops.config;

import com.dawnline.common.error.DomainException;
import com.dawnline.messaging.config.MessagingAutoConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * ops-api 보안 — JWT(HS256) 검증과 역할 (DESIGN.md §5.5 · §10, ADR-052 결정 2).
 *
 * <h2>검증만 한다</h2>
 * 토큰은 {@code make token ROLE=…} 이 같은 시크릿으로 찍는다(만료 12시간). 설계에 사용자 저장소가 없으므로
 * 로그인 엔드포인트를 두지 않는다 — 개발 프로필 한정이어도 「프로필이 꺼져 있다」는 조용한 전제가 하나 는다.
 *
 * <h2>무엇을 보나</h2>
 * 서명(HS256) · 발급자({@value #ISSUER}) · 만료(<strong>있어야 한다</strong>) · {@code sub}(감사 행의
 * {@code actor} 가 된다) · 역할 클레임 {@value #ROLES_CLAIM}.
 *
 * <h2>만료는 벽시계로 잰다 — 주입 시계가 아니다 (ADR-066 결정 5)</h2>
 * 토큰은 {@code tools/ops-token} 이 {@code date +%s} 로 찍는다 — 발급이 벽시계다. 검증을 주입 시계로 하면 시뮬레이션 오프셋
 * (프로필 {@code sim})이 켜진 스택에서 12시간 토큰이 오프셋만큼 일찍 만료된다 — 오프셋이 12시간을 넘으면 방금 찍은 토큰이 이미
 * 만료다. JWT 는 <strong>사실의 시각이 아니라 인프라의 시간</strong>이라 발급 · 검증 모두 벽시계다. 테스트는 {@link #decoder} 에
 * 시계를 넘겨 만료를 본다({@code OpsTokenScriptTest}).
 *
 * <h2>역할</h2>
 * 계층이다: {@code ADMIN} ⊃ {@code OPS_OPERATOR} ⊃ {@code OPS_VIEWER}. {@code GET} 은 {@code OPS_VIEWER},
 * 나머지 메서드는 {@code OPS_OPERATOR} — 커맨드는 전부 {@code POST} 다. 새 조회를 붙여도 뷰어에게 열리고
 * 새 커맨드를 붙여도 운영자에게만 열린다 — 경로를 열거하지 않는다.
 *
 * <p>인증 없이 여는 것은 프로브·스크레이프뿐이다({@code /actuator/health/readiness} 는 {@code make wait} 의
 * 폴링 대상, {@code /actuator/prometheus} 는 Prometheus 가 10초마다 긁는다).
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(OpsJwtProperties.class)
class SecurityConfig {

    /** {@code make token} 이 찍는 {@code iss}. */
    static final String ISSUER = "dawnline-ops-token";

    /** 역할 클레임. 값은 문자열 배열이다. */
    static final String ROLES_CLAIM = "roles";

    /** 인증 없이 열어 두는 경로. 운영 데이터가 아니라 프로브·스크레이프 전용이다. */
    private static final String[] PUBLIC_ENDPOINTS = {
        "/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus"
    };

    @Bean
    SecurityFilterChain opsSecurityFilterChain(HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        // 401·403 도 어드바이스를 지나 Problem Details 가 된다 (OpsAuthErrorCode). 필터 체인의 오류는 디스패처
        // 앞에서 나므로 어드바이스가 보지 못한다 — 해석기에 넘겨 같은 문을 지나게 한다.
        AuthenticationEntryPoint unauthenticated = (request, response, exception) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            resolver.resolveException(request, response, null, new DomainException(OpsAuthErrorCode.UNAUTHENTICATED,
                    "토큰이 없거나 유효하지 않습니다 — make token ROLE=… 로 발급한 토큰을 Authorization: Bearer 로 싣습니다"));
        };
        AccessDeniedHandler forbidden = (request, response, exception) ->
                resolver.resolveException(request, response, null, new DomainException(OpsAuthErrorCode.FORBIDDEN,
                        "이 역할로는 할 수 없는 요청입니다 — 조회는 OPS_VIEWER, 커맨드는 OPS_OPERATOR 이상"));

        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        return http
                // 상태 없는 REST API 라 CSRF 토큰을 쓰지 않는다(세션 쿠키 기반이 아니다).
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.GET, "/**").hasRole("OPS_VIEWER")
                        .anyRequest().hasRole("OPS_OPERATOR"))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .build();
    }

    @Bean
    static RoleHierarchy opsRoleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("ADMIN").implies("OPS_OPERATOR")
                .role("OPS_OPERATOR").implies("OPS_VIEWER")
                .build();
    }

    /**
     * @param properties 시크릿
     * @return 벽시계로 만료를 재는 검증기 — 주입 시계(시뮬레이션 오프셋)를 받지 않는다(ADR-066 결정 5)
     */
    @Bean
    JwtDecoder opsJwtDecoder(OpsJwtProperties properties) {
        return decoder(properties, MessagingAutoConfiguration.storagePrecisionClock());
    }

    /** 테스트가 같은 검증기를 쓰도록 빈 밖에서도 만든다. */
    static JwtDecoder decoder(OpsJwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(properties.secretBytes(), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(ISSUER),
                // 만료가 없는 토큰은 영원히 산다 — JwtTimestampValidator 는 exp 가 없으면 통과시킨다.
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank())));
        return decoder;
    }
}
