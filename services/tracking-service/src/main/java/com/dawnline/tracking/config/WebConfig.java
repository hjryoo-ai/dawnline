package com.dawnline.tracking.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API 버저닝 (DESIGN.md §5.4, [ADR-009](docs/adr/ADR-009-url-path-api-versioning.md)).
 *
 * <p>버전은 URL 경로 세그먼트다({@code /api/v1/routes/...}). Spring Framework 7 의 API 버저닝이
 * 그 세그먼트를 읽어 {@code @RequestMapping(version = "1")} 과 대조한다.
 *
 * <h2>경로에 이미 v1 이 있는데 왜 켜는가</h2>
 * 경로만으로도 {@code /api/v2/routes/...} 는 매핑되지 않는다. 그러나 그때 나가는 응답은
 * <strong>404</strong> — 「그런 리소스가 없다」다. 사실은 「그 리소스는 있는데 그 버전은 지원하지
 * 않는다」이고, 버저닝을 켜면 그렇게 답한다. 기사 단말은 앱 업데이트가 늦은 기기가 섞여 있는
 * 집단이라, 이 구별이 특히 값을 한다 — 404 를 보면 라우트가 사라진 줄 알게 된다.
 *
 * <h2>술어가 필요한 이유</h2>
 * {@code usePathSegment(1)} 만 두면 <em>모든</em> 요청의 두 번째 세그먼트를 버전으로 읽으려 든다.
 * {@code /actuator/health} 의 두 번째 세그먼트는 {@code health} 이고, 그것을 버전으로 파싱하면
 * 헬스 체크가 깨진다 — 레디니스 프로브가 실패하면 배포가 멈춘다(§8.6).
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    /** 버전 세그먼트가 있는 경로의 접두어. */
    private static final String VERSIONED_PREFIX = "/api/";

    /** {@code /api/v1/routes} 에서 {@code v1} 의 위치 (0부터). */
    private static final int VERSION_SEGMENT_INDEX = 1;

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer
                .usePathSegment(VERSION_SEGMENT_INDEX, path -> path.value().startsWith(VERSIONED_PREFIX))
                .addSupportedVersions("1");
    }
}
