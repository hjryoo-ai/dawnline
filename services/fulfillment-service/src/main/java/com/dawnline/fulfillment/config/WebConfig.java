package com.dawnline.fulfillment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API 버저닝 (DESIGN.md §5.2, [ADR-009](docs/adr/ADR-009-url-path-api-versioning.md)).
 *
 * <p>버전은 URL 경로 세그먼트다({@code /api/v1/waves}). Spring Framework 7 의 API 버저닝이 그 세그먼트를 읽어
 * {@code @RequestMapping(version = "1")} 과 대조한다. 첫 REST 표면(ADR-054)과 함께 왔다 — dispatch 가 이것을
 * 늦게 받아 리터럴 {@code /api/v1} 로 들어왔던 일(2026-09-19)을 되풀이하지 않는다.
 *
 * <p>술어가 {@code /api/} 로 좁히는 이유는 ADR-009 결정 3 이다 — {@code /actuator/health} 의 두 번째 세그먼트를
 * 버전으로 파싱하게 두지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    /** 버전 세그먼트가 있는 경로의 접두어. */
    private static final String VERSIONED_PREFIX = "/api/";

    /** {@code /api/v1/waves} 에서 {@code v1} 의 위치 (0부터). */
    private static final int VERSION_SEGMENT_INDEX = 1;

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer
                .usePathSegment(VERSION_SEGMENT_INDEX, path -> path.value().startsWith(VERSIONED_PREFIX))
                .addSupportedVersions("1");
    }
}
