package com.dawnline.ops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * ops-api 최소 보안 설정 (DESIGN.md §5.5, §10).
 *
 * <p>ops-api 는 코어 서비스와 달리 {@code spring-boot-starter-security} 를 쓴다. 기본
 * 자동 설정은 <b>모든</b> 요청에 인증을 요구하므로, 그대로 두면 다음 두 가지가 깨진다.
 * <ul>
 *   <li>{@code /actuator/health/readiness} — Phase 0 DoD 이자 {@code make wait} 의 폴링 대상</li>
 *   <li>{@code /actuator/prometheus} — Prometheus 가 인증 없이 10초마다 긁는다
 *       (deploy/compose/prometheus/prometheus.yml)</li>
 * </ul>
 * 그래서 이 두 경로만 열고 나머지는 인증을 요구한다. 직접
 * {@link SecurityFilterChain} 빈을 정의하면 Boot 의 기본 체인과
 * {@code ManagementWebSecurityAutoConfiguration} 이 물러나므로, 액추에이터 예외도
 * 여기서 함께 정한다.
 *
 * <p><b>Phase 6 에서 교체한다</b>: JWT(HS256) 인증과 역할
 * ({@code OPS_VIEWER}/{@code OPS_OPERATOR}/{@code ADMIN}), 커맨드 엔드포인트 권한,
 * {@code audit_logs} 기록이 §5.5 의 최종 형태다. 지금은 HTTP Basic(부팅 시 생성되는
 * 임시 비밀번호)만 걸어 둔 골격이다.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    /** 인증 없이 열어 두는 경로. 운영 데이터가 아니라 프로브·스크레이프 전용이다. */
    private static final String[] PUBLIC_ENDPOINTS = {
        "/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus"
    };

    @Bean
    SecurityFilterChain opsSecurityFilterChain(HttpSecurity http) {
        return http
                // 상태 없는 REST API 라 CSRF 토큰을 쓰지 않는다(세션 쿠키 기반이 아니다).
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers(PUBLIC_ENDPOINTS)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}
