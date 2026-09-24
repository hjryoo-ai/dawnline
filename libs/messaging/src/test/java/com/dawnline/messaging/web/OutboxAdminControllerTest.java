package com.dawnline.messaging.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dawnline.messaging.config.OutboxAdminAutoConfiguration;
import com.dawnline.messaging.outbox.OutboxEvent;
import com.dawnline.messaging.outbox.OutboxRepository;
import com.dawnline.messaging.support.InMemoryOutboxRepository;
import com.dawnline.messaging.support.MutableClock;
import com.dawnline.messaging.support.TestTransactionManager;
import com.dawnline.web.ProblemDetailsAdviceSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 격리 조회·재큐의 HTTP 모양 (DESIGN.md §4.6 「격리 조회·재큐 엔드포인트」).
 *
 * <p>슬라이스가 아니라 <strong>자동 설정이 등록한 그 컨트롤러</strong>를 부른다 — 서비스에 붙는 경로가 이것뿐이다.
 * 버저닝과 어드바이스는 서비스의 것과 같은 형태로 둔다(서비스의 {@code WebConfig}, {@code ProblemDetailsAdviceSupport}
 * 하위 클래스). 오류 본문의 {@code code} 는 그 어드바이스가 붙인다.
 */
class OutboxAdminControllerTest {

    private final MutableClock clock = MutableClock.at("2026-09-24T01:00:00Z");
    private final InMemoryOutboxRepository repository = new InMemoryOutboxRepository(clock);

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class,
                    OutboxAdminAutoConfiguration.class))
            .withUserConfiguration(ServiceLikeWeb.class)
            .withBean(OutboxRepository.class, () -> repository)
            .withBean(PlatformTransactionManager.class, TestTransactionManager::new);

    @Test
    void 목록은_격리된_행을_싣고_payload_와_headers_는_싣지_않는다() {
        OutboxEvent row = quarantined();
        pending();

        withMockMvc(mvc -> mvc.perform(get("/api/v1/admin/outbox/quarantined"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].id").value(row.id().toString()))
                .andExpect(jsonPath("$.events[0].eventType").value("order.placed"))
                .andExpect(jsonPath("$.events[0].topic").value("dawnline.order.placed.v1"))
                .andExpect(jsonPath("$.events[0].failedAt").value("2026-09-24T00:10:00Z"))
                .andExpect(jsonPath("$.events[0].publishAttempts").value(1))
                // §9.3 — 셋 다 주소를 담을 수 있다.
                .andExpect(jsonPath("$.events[0].payload").doesNotExist())
                .andExpect(jsonPath("$.events[0].headers").doesNotExist())
                .andExpect(jsonPath("$.events[0].partitionKey").doesNotExist()));
    }

    @Test
    void 목록의_limit_이_범위_밖이면_400_이다() {
        withMockMvc(mvc -> mvc.perform(get("/api/v1/admin/outbox/quarantined").param("limit", "501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.field").value("limit")));
    }

    @Test
    void 재큐는_격리를_풀고_200_이다() {
        OutboxEvent row = quarantined();

        withMockMvc(mvc -> mvc.perform(post("/api/v1/admin/outbox/{id}/requeue", row.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(row.id().toString()))
                .andExpect(jsonPath("$.eventType").value("order.placed"))
                .andExpect(jsonPath("$.aggregateType").value("Order")));
        assertThat(repository.countFailed()).isZero();
    }

    @Test
    void 없는_행의_재큐는_404_다() {
        withMockMvc(mvc -> mvc.perform(post("/api/v1/admin/outbox/{id}/requeue", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found")));
    }

    @Test
    void 격리되지_않은_행의_재큐는_409_이고_지금_위치가_본문_최상위에_있다() {
        OutboxEvent row = pending();

        withMockMvc(mvc -> mvc.perform(post("/api/v1/admin/outbox/{id}/requeue", row.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not-quarantined"))
                .andExpect(jsonPath("$.type").value(ProblemDetailsAdviceSupport.PROBLEM_TYPE_PREFIX + "not-quarantined"))
                // ops-api 는 본문을 바이트 그대로 넘긴다(§5.5) — 운영자가 읽는 칸이 최상위여야 한다.
                .andExpect(jsonPath("$.currentState").value("PENDING")));
    }

    @Test
    void id_가_UUID_가_아니면_400_이다() {
        withMockMvc(mvc -> mvc.perform(post("/api/v1/admin/outbox/{id}/requeue", "not-a-uuid"))
                .andExpect(status().isBadRequest()));
    }

    @Test
    void 지원하지_않는_버전은_404_가_아니라_400_이다() {
        // 매핑이 {version} 이라 경로에서 떨어지지 않고 버전 조건까지 온다 (ADR-009 결정 2, ArchUnit 규칙 8 의 이유).
        withMockMvc(mvc -> mvc.perform(get("/api/v2/admin/outbox/quarantined"))
                .andExpect(status().isBadRequest()));
    }

    private void withMockMvc(ThrowingConsumer<MockMvc> body) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = MockMvcBuilders
                    .webAppContextSetup((WebApplicationContext) context.getSourceApplicationContext())
                    .build();
            body.accept(mvc);
        });
    }

    private OutboxEvent pending() {
        clock.advance(Duration.ofSeconds(1));
        UUID id = UUID.randomUUID();
        OutboxEvent row = new OutboxEvent(id, "Order", UUID.randomUUID(), "order.placed", "dawnline.order.placed.v1",
                id.toString(), "{\"eventType\":\"order.placed\",\"schemaVersion\":\"1\",\"address\":\"x\"}",
                "{\"address\":\"서울\"}", clock.instant());
        repository.append(row);
        return row;
    }

    private OutboxEvent quarantined() {
        OutboxEvent row = pending();
        row.markFailed(Instant.parse("2026-09-24T00:10:00Z"));
        return row;
    }

    /** 예외를 던지는 소비자 — MockMvc 의 {@code perform} 이 checked 예외를 던진다. */
    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }

    /** 서비스의 {@code WebConfig} 와 {@code ProblemDetailsAdvice} 와 같은 형태. */
    @Configuration(proxyBeanMethods = false)
    static class ServiceLikeWeb implements WebMvcConfigurer {

        @Override
        public void configureApiVersioning(ApiVersionConfigurer configurer) {
            configurer.usePathSegment(1, path -> path.value().startsWith("/api/")).addSupportedVersions("1");
        }

        @Bean
        Advice advice() {
            return new Advice();
        }
    }

    /** 서비스의 단일 어드바이스. */
    @RestControllerAdvice
    static class Advice extends ProblemDetailsAdviceSupport {

        @Override
        protected Map<String, Integer> retryAfterSeconds() {
            return Map.of();
        }
    }
}
