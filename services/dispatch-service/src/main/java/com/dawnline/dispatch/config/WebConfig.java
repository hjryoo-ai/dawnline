package com.dawnline.dispatch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API 버저닝 (DESIGN.md §5.3, [ADR-009](docs/adr/ADR-009-url-path-api-versioning.md)).
 *
 * <p>버전은 URL 경로 세그먼트다({@code /api/v1/plans}). Spring Framework 7 의 API 버저닝이 그
 * 세그먼트를 읽어 {@code @RequestMapping(version = "1")} 과 대조한다.
 *
 * <h2>이 서비스에 늦게 왔다</h2>
 * ADR-009 는 order-service 의 첫 컨트롤러와 함께 쓰였고 그 서비스는 지켰지만, dispatch 의 컨트롤러
 * 셋은 리터럴 {@code /api/v1} 로 들어왔다(2026-09-19 에 ArchUnit 규칙 8 이 잡았다). 「운영자 API
 * 라서 다르다」는 <strong>ADR 에 없는 예외</strong>이고, 예외를 둘 거면 ADR 이 먼저다. 주소는
 * 그대로이고 달라지는 것은 지원하지 않는 버전의 응답이다 — 404 가 아니라 400 이 된다.
 *
 * <h2>술어가 왜 있는가</h2>
 * {@code usePathSegment(1)} 만 두면 <em>모든</em> 요청의 두 번째 세그먼트를 버전으로 읽으려 든다.
 * {@code /actuator/health} 의 두 번째 세그먼트는 {@code health} 이고, 그것을 버전으로 파싱하는
 * 것이 무해하다고 볼 이유가 없다. ADR-009 결정 3 은 그 자리를 <strong>방어적으로</strong> 좁혀
 * 두기로 했다 — 근거였던 음성 표본이 Boot 4.1.x 에서 더 이상 재현되지 않는다는 사실은 그 ADR 의
 * 후속 정정에 적혀 있다.
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
