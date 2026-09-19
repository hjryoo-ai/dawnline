package com.dawnline.tracking.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 문서 (DESIGN.md §5.4, §14 — {@code contracts/openapi/tracking-service.yaml}).
 *
 * <p>생성된 문서는 저장소에 커밋되고 {@code OpenApiContractIT} 가 코드와 어긋나지 않는지
 * 검사한다. 문서를 손으로 고치는 순간 그 테스트가 깨진다.
 *
 * <p>이 문서를 읽는 쪽이 사람만은 아니다 — Phase 5-2 의 {@code sim-runner} 기사 시뮬레이터가
 * <strong>다른 모듈에서</strong> 이 엔드포인트를 부른다(§5.6). 두 모듈이 공유하는 것이 이
 * 문서뿐이므로, 문서가 코드와 어긋나는 순간 그 어긋남은 시뮬레이터의 런타임 오류가 된다.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /** 문서에 적히는 API 버전. 경로 세그먼트 {@code v1} 과 같은 major 다 (ADR-009). */
    private static final String API_VERSION = "1.0.0";

    /** 로컬 기본 포트 (application.yml 의 {@code server.port}). */
    private static final String LOCAL_SERVER = "http://localhost:8084";

    /** springdoc 이 {@code {version}} 자리표시자를 채운 결과. */
    private static final String RESOLVED_VERSION_PREFIX = "/api/1/";

    /** 설계서(§5.4)가 쓰는 정식 형태. */
    private static final String CANONICAL_VERSION_PREFIX = "/api/v1/";

    /** OpenAPI 문서의 머리말. */
    @Bean
    public OpenAPI trackingServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Dawnline tracking-service")
                        .version(API_VERSION)
                        .description("""
                                기사 스캔 API (DESIGN.md §5.4). 배송 진행·ETA·지연 위험은 이 스캔에서 시작한다.

                                **인증이 없다.** 고객 API 와 같은 결정이고(§10) 같은 대가를 치른다 — \
                                라우트 id 를 아는 사람은 누구나 그 라우트의 배송 상태를 바꿀 수 있다. \
                                라우트 id 가 UUIDv7(무작위 74비트)이라는 것이 사실상 유일한 방어다.

                                **재시도해도 안전하다.** 멱등 키 헤더가 없다 — 멱등은 상태 머신이 만든다(§8.5). \
                                같은 스캔이 다시 오면 이미 지나온 지점이라 `STALE` 로 흡수되고, 상태는 그대로다. \
                                오프라인에서 다시 켜진 단말이 밀린 스캔을 순서 없이 보내도 같은 규칙이 흡수한다.

                                **취소 뒤의 스캔도 200 이다.** 기사가 취소를 받지 못하고 배송한 경우이고 \
                                기사가 고칠 수 있는 문제가 아니다. 해당 주문 줄이 `AFTER_CANCEL` 로 온다.

                                오류 응답은 RFC 9457 Problem Details 이고 `type` 과 `code` 가 항상 채워진다."""))
                .servers(List.of(new Server().url(LOCAL_SERVER).description("로컬 개발")))
                .components(new Components());
    }

    /**
     * 경로의 버전 자리표시자를 <strong>단말이 실제로 부르는 형태</strong>로 되돌린다.
     *
     * <p>컨트롤러 매핑은 {@code /api/{version}/routes/...} 다(ADR-009). springdoc 은 그 자리를
     * <em>해석된 버전 값</em>으로 채워 {@code /api/1/routes/...} 를 적는다. 그 주소로도 요청은
     * 통하지만 설계서(§5.4)가 쓰는 정식 주소가 아니고, 문서를 보고 만든 클라이언트가 우리가
     * 지원한다고 말한 적 없는 형태를 쓰게 된다.
     */
    @Bean
    public OpenApiCustomizer canonicalVersionPathCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            Paths canonical = new Paths();
            canonical.extensions(openApi.getPaths().getExtensions());
            openApi.getPaths().forEach((path, item) ->
                    canonical.addPathItem(path.replace(RESOLVED_VERSION_PREFIX, CANONICAL_VERSION_PREFIX), item));
            openApi.setPaths(canonical);
        };
    }
}
