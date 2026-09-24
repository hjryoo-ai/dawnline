package com.dawnline.ops.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API 버저닝 (DESIGN.md §5.5, [ADR-009](docs/adr/ADR-009-url-path-api-versioning.md)).
 *
 * <p>다른 네 서비스와 같다 — 버전은 URL 경로 세그먼트이고({@code /api/v1/plans/…/run}) 매핑은
 * {@code {version}} 자리표시자로 적는다(ArchUnit 규칙 8). ops-api 의 커맨드 경로가 코어의 경로와 같으므로
 * 버전 세그먼트도 같은 자리에 있다.
 *
 * <p>술어는 {@code /api/} 로 시작하는 경로만 버전을 읽게 좁힌다 — 액추에이터의 두 번째 세그먼트를
 * 버전으로 파싱하지 않는다(ADR-009 결정 3).
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    /** 버전 세그먼트가 있는 경로의 접두어. */
    private static final String VERSIONED_PREFIX = "/api/";

    /** {@code /api/v1/plans} 에서 {@code v1} 의 위치 (0부터). */
    private static final int VERSION_SEGMENT_INDEX = 1;

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer
                .usePathSegment(VERSION_SEGMENT_INDEX, path -> path.value().startsWith(VERSIONED_PREFIX))
                .addSupportedVersions("1");
    }
}
