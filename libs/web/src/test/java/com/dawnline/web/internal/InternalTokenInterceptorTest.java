package com.dawnline.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dawnline.web.ProblemDetailsAdviceSupport;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 내부 토큰 인터셉터 (DESIGN.md §10 셋째 층, ADR-055 결정 2).
 *
 * <p>자동 설정이 등록한 그 인터셉터를 서비스와 같은 모양(버저닝 + 단일 어드바이스)의 웹 앱 위에서 부른다. 코어에서
 * 실제로 도는지는 코어 넷의 {@code OpenApiContractIT} 가 문서에서 뽑은 쓰기로 본다.
 */
class InternalTokenInterceptorTest {

    private static final String TOKEN = "unit-test-only-internal-token-0123456789";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class,
                    InternalTokenAutoConfiguration.class))
            .withUserConfiguration(ServiceLikeWeb.class)
            .withPropertyValues(InternalToken.PROPERTY_PREFIX + ".secret=" + TOKEN);

    private final Logger logger = (Logger) LoggerFactory.getLogger(InternalTokenInterceptor.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void attachLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void 토큰_없는_쓰기는_401_이고_본문은_Problem_Details_다() {
        withMockMvc(mvc -> mvc.perform(post("/api/v1/things/close"))
                .andExpect(status().isUnauthorized())
                // 같은 문 — 어드바이스가 만든 본문이다(인터셉터가 직접 쓰지 않는다).
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("internal-token-required"))
                .andExpect(jsonPath("$.type").value(ProblemDetailsAdviceSupport.PROBLEM_TYPE_PREFIX
                        + "internal-token-required"))
                .andExpect(jsonPath("$.instance").value("/api/v1/things/close")));
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .singleElement().asString().contains("reason=missing").doesNotContain(TOKEN);
    }

    @Test
    void 틀린_토큰도_401_이고_로그는_값을_적지_않는다() {
        String wrong = "wrong-token-that-is-long-enough-0123456789";

        withMockMvc(mvc -> mvc.perform(put("/api/v1/things/1").header(InternalToken.HEADER, wrong))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("internal-token-required")));
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .singleElement().asString().contains("reason=mismatch").doesNotContain(wrong);
    }

    @Test
    void 앞부분만_같은_토큰도_401_이다() {
        withMockMvc(mvc -> mvc.perform(post("/api/v1/things/close")
                        .header(InternalToken.HEADER, TOKEN.substring(0, TOKEN.length() - 1)))
                .andExpect(status().isUnauthorized()));
    }

    @Test
    void 맞는_토큰이면_지나간다() {
        withMockMvc(mvc -> {
            mvc.perform(post("/api/v1/things/close").header(InternalToken.HEADER, TOKEN)).andExpect(status().isOk());
            mvc.perform(put("/api/v1/things/1").header(InternalToken.HEADER, TOKEN)).andExpect(status().isOk());
            mvc.perform(delete("/api/v1/things/1").header(InternalToken.HEADER, TOKEN)).andExpect(status().isOk());
        });
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 읽기는_대상이_아니다() {
        withMockMvc(mvc -> mvc.perform(get("/api/v1/things/1")).andExpect(status().isOk()));
    }

    @Test
    void 면제_표시가_붙은_쓰기는_토큰_없이_지나간다() {
        withMockMvc(mvc -> mvc.perform(post("/api/v1/things")).andExpect(status().isOk()));
    }

    @Test
    void 모르는_경로는_401_이_아니라_404_다() {
        // 매핑 뒤에 서는 이유 — 필터였다면 여기가 401 이다.
        withMockMvc(mvc -> mvc.perform(post("/api/v1/nowhere")).andExpect(status().isNotFound()));
    }

    @Test
    void 지원하지_않는_버전은_401_이_아니라_400_이다() {
        withMockMvc(mvc -> mvc.perform(post("/api/v2/things/close")).andExpect(status().isBadRequest()));
    }

    @Test
    void 검사를_끄면_인터셉터가_없다() {
        // ops-api 의 모양 — 그 쓰기는 JWT·역할·감사가 지킨다(ADR-055 결정 4). 값은 여전히 바인딩된다.
        runner.withPropertyValues(InternalToken.PROPERTY_PREFIX + ".enforce=false").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(InternalTokenProperties.class)
                    .doesNotHaveBean("dawnlineInternalTokenEnforcement");
            MockMvcBuilders.webAppContextSetup((WebApplicationContext) context.getSourceApplicationContext()).build()
                    .perform(post("/api/v1/things/close")).andExpect(status().isOk());
        });
    }

    @Test
    void 토큰이_없으면_뜨지_않는다() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(InternalTokenAutoConfiguration.class))
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("DAWNLINE_INTERNAL_TOKEN 이 없다"));
    }

    private void withMockMvc(ThrowingConsumer<MockMvc> body) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            body.accept(MockMvcBuilders
                    .webAppContextSetup((WebApplicationContext) context.getSourceApplicationContext())
                    .build());
        });
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }

    /** 서비스의 {@code WebConfig} 와 단일 어드바이스와 같은 모양, 그리고 쓰기 넷·읽기 하나. */
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

        @Bean
        Things things() {
            return new Things();
        }
    }

    @RestControllerAdvice
    static class Advice extends ProblemDetailsAdviceSupport {

        @Override
        protected Map<String, Integer> retryAfterSeconds() {
            return Map.of();
        }
    }

    @RestController
    @RequestMapping(path = "/api/{version}/things", version = "1")
    static class Things {

        @PostMapping
        @UnauthenticatedWrite(reason = "표본 — 고객 표면")
        String place() {
            return "placed";
        }

        @PostMapping("/close")
        String close() {
            return "closed";
        }

        @PutMapping("/{id}")
        String replace(@PathVariable String id) {
            return id;
        }

        @DeleteMapping("/{id}")
        String remove(@PathVariable String id) {
            return id;
        }

        @GetMapping("/{id}")
        String read(@PathVariable String id) {
            return id;
        }
    }
}
