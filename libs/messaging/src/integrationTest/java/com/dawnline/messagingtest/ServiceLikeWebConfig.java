package com.dawnline.messagingtest;

import com.dawnline.web.ProblemDetailsAdviceSupport;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 서비스의 {@code WebConfig}(API 버저닝)와 같은 형태.
 *
 * <p>테스트 클래스패스에 {@code spring-webmvc} 가 들어오면서(outbox 격리 조회·재큐, §4.6) 이 테스트 앱이 서블릿 웹
 * 앱이 됐고, 그러면 {@code OutboxAdminAutoConfiguration} 이 컨트롤러를 붙인다. 그 매핑은 {@code {version}} 이라
 * 버저닝이 설정되지 않은 앱에서는 기동이 거절된다 — 서비스 다섯이 모두 이것을 갖고 있고, 여기도 같은 조건에 선다.
 */
@Configuration(proxyBeanMethods = false)
public class ServiceLikeWebConfig implements WebMvcConfigurer {

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer.usePathSegment(1, path -> path.value().startsWith("/api/")).addSupportedVersions("1");
    }

    /** 서비스의 단일 어드바이스와 같은 형태 — 오류 본문의 {@code code} 가 여기서 붙는다. */
    @RestControllerAdvice
    public static class Advice extends ProblemDetailsAdviceSupport {

        @Override
        protected Map<String, Integer> retryAfterSeconds() {
            return Map.of();
        }
    }
}
