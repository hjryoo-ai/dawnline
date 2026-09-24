package com.dawnline.fulfillment.config;

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
 * OpenAPI 문서 (DESIGN.md §5.2 · §11 — {@code contracts/openapi/fulfillment-service.yaml}).
 *
 * <p>생성된 문서는 저장소에 커밋되고 {@code OpenApiContractIT} 가 코드와 어긋나지 않는지 검사한다.
 *
 * <p><strong>왜 지금인가</strong>: springdoc 은 Phase 2 부터 붙어 있었지만 REST 표면이 없었다. 첫 운영
 * 엔드포인트(조기 마감, ADR-054)와 첫 소비자(ops-api 의 위임 클라이언트, ADR-052)가 같은 작업에서 왔다 —
 * 부재는 첫 소비자가 나타나는 시점에 채운다(§11).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /** 문서에 적히는 API 버전. 경로 세그먼트 {@code v1} 과 같은 major 다 (ADR-009). */
    private static final String API_VERSION = "1.0.0";

    /** 로컬 기본 포트 (application.yml 의 {@code server.port}). */
    private static final String LOCAL_SERVER = "http://localhost:8082";

    /** springdoc 이 {@code {version}} 자리표시자를 채운 결과. */
    private static final String RESOLVED_VERSION_PREFIX = "/api/1/";

    /** 설계서가 쓰는 정식 형태. */
    private static final String CANONICAL_VERSION_PREFIX = "/api/v1/";

    /** OpenAPI 문서의 머리말. */
    @Bean
    public OpenAPI fulfillmentServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Dawnline fulfillment-service")
                        .version(API_VERSION)
                        .description("""
                                웨이브 운영 API (DESIGN.md §5.2). 지금은 조기 마감 하나다.

                                **고객 API 가 아니다.** 이 표면은 ops-api 를 통해서만 노출된다 \
                                (불변규칙 4 — 코어 서비스 간 동기 호출은 금지이고 ops-api → 코어 방향만 있다). \
                                그래서 §7.2 의 고객 레이트 리밋이 붙지 않고, 인증은 ops-api 가 맡는다.

                                **조기 마감은 약속을 개정한다.** 컷오프 전에 닫으면 컷오프까지 같은 `cutoffAt` \
                                으로 접수되는 주문은 다음 웨이브로 가고 `fulfillment.planned` 에 개정된 창이 실린다 \
                                (ADR-054). 그래서 `reason` 이 필수다.

                                **조기 마감은 멱등이 아니다 — 두 번째 요청은 말한다.** 이미 닫힌 웨이브는 \
                                409 `wave-not-open` 이고 `closeCause` 가 누가 닫았는지 알려 준다. `Retry-After` 를 \
                                쓰는 오류는 없다.

                                오류 응답은 RFC 9457 Problem Details 이고 `type` 과 `code` 가 항상 채워진다."""))
                .servers(List.of(new Server().url(LOCAL_SERVER).description("로컬 개발")))
                .components(new Components());
    }

    /**
     * 경로의 버전 자리표시자를 <strong>호출자가 실제로 부르는 형태</strong>로 되돌린다.
     *
     * <p>컨트롤러 매핑은 {@code /api/{version}/...} 다(ADR-009). springdoc 은 그 자리를 해석된 버전 값으로 채워
     * {@code /api/1/...} 을 적는다. 그 주소로도 요청은 통하지만 설계서가 쓰는 정식 주소가 아니고, 문서를 보고
     * 만든 클라이언트(ops-api)가 우리가 지원한다고 말한 적 없는 형태를 쓰게 된다.
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
