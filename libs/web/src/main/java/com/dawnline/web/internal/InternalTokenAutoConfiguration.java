package com.dawnline.web.internal;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 내부 토큰을 {@code libs/web} 을 쓰는 서블릿 웹 앱 전부에 건다 (DESIGN.md §10, ADR-055).
 *
 * <p>서비스마다 적게 하지 않는다 — 한 서비스가 그 한 줄을 잊으면 그 서비스의 쓰기가 조용히 열린다. 그래도 새는
 * 경우(누가 이 설정을 제외하는 등)를 잡는 것은 이 클래스가 아니라 코어의 {@code OpenApiContractIT} 다: 문서에서 뽑은
 * 쓰기를 전부 토큰 없이 불러 401 을 본다 — 장치가 아니라 결과를 본다.
 *
 * <p>설정 값은 {@code enforce} 와 무관하게 바인딩·검증한다. 검사를 끈 ops-api 도 이 값을 코어 호출에 싣는다.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(InternalTokenProperties.class)
public class InternalTokenAutoConfiguration {

    /** 검사. ops-api 는 끈다 — 그 쓰기는 JWT·역할·감사가 지킨다(ADR-055 결정 4). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = InternalToken.PROPERTY_PREFIX, name = "enforce", havingValue = "true",
            matchIfMissing = true)
    static class Enforcement {

        /**
         * @param properties 검증된 토큰 설정
         * @return 인터셉터를 등록하는 설정
         */
        @Bean
        WebMvcConfigurer dawnlineInternalTokenEnforcement(InternalTokenProperties properties) {
            InternalTokenInterceptor interceptor = new InternalTokenInterceptor(properties);
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(interceptor);
                }
            };
        }

        /** 문서가 토큰을 말한다 — springdoc 을 쓰는 서비스만. */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = "org.springdoc.core.customizers.OperationCustomizer")
        static class Documentation {

            @Bean
            OperationCustomizer dawnlineInternalTokenOperations() {
                return InternalTokenOpenApi.operations();
            }

            @Bean
            OpenApiCustomizer dawnlineInternalTokenScheme() {
                return InternalTokenOpenApi.scheme();
            }
        }
    }
}
