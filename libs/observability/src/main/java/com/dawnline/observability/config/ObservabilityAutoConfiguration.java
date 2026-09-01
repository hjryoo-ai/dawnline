package com.dawnline.observability.config;

import com.dawnline.observability.MdcFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * {@code libs/observability} 자동 구성 (DESIGN.md §9.2, §9.3).
 *
 * <p>여기서 하는 일은 <strong>서블릿 MDC 필터 등록 하나</strong>다.
 * 나머지 관측성 배선은 Spring Boot 4.1 이 이미 자동으로 해 준다 — 직접 다시 만들지 않는다
 * (CLAUDE.md: 새 코드 추가 최소화).
 *
 * <h2>Boot 4.1 이 자동으로 해 주는 것</h2>
 * <ul>
 *   <li><b>트레이싱</b>: {@code spring-boot-starter-opentelemetry} 를 넣으면
 *       {@code OpenTelemetryTracingAutoConfiguration} 이 {@code OtelTracer},
 *       W3C {@code Propagator}, 그리고 {@code traceId}/{@code spanId} 를 MDC 에 넣는
 *       {@code Slf4JEventListener} 를 만든다.</li>
 *   <li><b>OTLP 내보내기</b>: {@code OtlpTracingAutoConfiguration} 이
 *       {@code management.opentelemetry.tracing.export.otlp.endpoint} 로 스팬을 보낸다.</li>
 *   <li><b>HTTP 서버/클라이언트 스팬</b>: Micrometer Observation 자동 구성.</li>
 *   <li><b>Kafka 헤더 {@code traceparent} 전파</b>: Spring Kafka 4.1 의 observation.
 *       단, {@code spring.kafka.template.observation-enabled} 와
 *       {@code spring.kafka.listener.observation-enabled} 가 <b>기본값 false</b> 라서
 *       명시적으로 켜야 한다({@code observability-defaults.yml} 이 켠다).</li>
 *   <li><b>구조화 JSON 로그</b>: {@code logging.structured.format.console=logstash} 만 주면
 *       Boot 의 {@code StructuredLogEncoder} 가 MDC 를 포함해 JSON 으로 찍는다.
 *       커스텀 인코더가 필요 없다.</li>
 *   <li><b>Prometheus 노출</b>: {@code micrometer-registry-prometheus} + actuator.</li>
 * </ul>
 *
 * <h2>수동으로 해야 하는 것</h2>
 * <ul>
 *   <li>이 클래스가 등록하는 MDC 필터(서비스 이름 주입 + 요청 종료 시 MDC 정리).</li>
 *   <li>Kafka 리스너·릴레이·스케줄러에서 {@code com.dawnline.observability.MdcScope} 로
 *       {@code eventId}/{@code orderId}/{@code waveId}/{@code routeId} 를 넣는 일.</li>
 *   <li>§9.1 커스텀 메트릭 등록 — 값을 아는 서비스가
 *       {@code com.dawnline.observability.DawnlineMetrics} 상수로 만든다.</li>
 *   <li>공통 프로퍼티 기본값 적용:
 *       {@code spring.config.import: "optional:classpath:/com/dawnline/observability/observability-defaults.yml"}</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ObservabilityAutoConfiguration {

    /**
     * MDC 필터를 가장 앞쪽에 등록한다.
     *
     * <p>순서를 앞에 두는 이유: 뒤따르는 보안 필터·에러 처리 필터가 남기는 로그에도
     * {@code service} 필드가 붙어야 한다. 다만 트레이싱 필터보다 앞이든 뒤든
     * {@code traceId} 는 Micrometer 가 스팬 스코프 기준으로 넣으므로 영향받지 않는다.
     *
     * @param environment {@code spring.application.name} 을 읽는다. 없으면 {@code unknown}.
     */
    @Bean
    @ConditionalOnMissingBean(name = "dawnlineMdcFilterRegistration")
    public FilterRegistrationBean<MdcFilter> dawnlineMdcFilterRegistration(Environment environment) {
        String serviceName = environment.getProperty("spring.application.name", "unknown");
        FilterRegistrationBean<MdcFilter> registration =
                new FilterRegistrationBean<>(new MdcFilter(serviceName));
        registration.setName("dawnlineMdcFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
