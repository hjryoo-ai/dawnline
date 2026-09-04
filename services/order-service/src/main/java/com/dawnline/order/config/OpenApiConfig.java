package com.dawnline.order.config;

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
 * OpenAPI 문서 (DESIGN.md §5.1, §14 — {@code contracts/openapi/order-service.yaml}).
 *
 * <p>생성된 문서는 저장소에 커밋되고 {@code OpenApiContractIT} 가 <strong>코드와 어긋나지
 * 않는지</strong> 검사한다. 문서를 손으로 고치는 순간 그 테스트가 깨진다 — API 문서가 코드보다
 * 늦게 따라오는 흔한 상태를 막는 유일한 방법이다.
 *
 * <h2>인증 스킴을 넣지 않는다</h2>
 * 고객 API 는 무인증이다(§10, §17-2). 빈 {@code securitySchemes} 를 두면 "인증이 있는데 문서에
 * 안 적힌 것" 처럼 읽힌다. 대신 설명에 그 사실과 대가를 적는다 — 문서를 읽는 사람이 알아야 할
 * 것은 "키를 어디서 받나" 가 아니라 "이 API 는 호출자를 검증하지 않는다" 이다.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /** 문서에 적히는 API 버전. 경로 세그먼트 {@code v1} 과 같은 major 다 (ADR-009). */
    private static final String API_VERSION = "1.0.0";

    /** 로컬 기본 포트 (application.yml 의 {@code server.port}). */
    private static final String LOCAL_SERVER = "http://localhost:8081";

    /** springdoc 이 {@code {version}} 자리표시자를 채운 결과. */
    private static final String RESOLVED_VERSION_PREFIX = "/api/1/";

    /** 설계서(§5.1)와 {@code Location} 헤더가 쓰는 정식 형태. */
    private static final String CANONICAL_VERSION_PREFIX = "/api/v1/";

    /** OpenAPI 문서의 머리말. */
    @Bean
    public OpenAPI orderServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Dawnline order-service")
                        .version(API_VERSION)
                        .description("""
                                주문 접수·조회·취소 API (DESIGN.md §5.1).

                                **인증이 없다.** 의도된 결정이며(§10) 대가도 분명하다 — `customerId` 는 \
                                클라이언트 주장값이고, 주문 id 를 아는 사람은 누구나 그 주문을 읽고 취소할 수 있다. \
                                주문 id 가 UUIDv7(무작위 74비트)이라는 것이 사실상 유일한 방어다. \
                                남용 방지는 고객별 레이트 리밋이 담당한다(§7.2, 분당 60을 넘는 지속 부하 차단).

                                **`POST /orders` 는 `Idempotency-Key` 헤더가 필수다.** 같은 키의 재요청은 \
                                저장된 응답을 200 으로 재생하고, 같은 키에 다른 본문이면 422 다. \
                                보존은 7일이며 그 뒤의 같은 키는 새 주문이 된다(ADR-019).

                                오류 응답은 RFC 9457 Problem Details 이고 `type` 과 `code` 가 항상 채워진다.""")
                        .termsOfService("https://dawnline.internal/terms"))
                .servers(List.of(new Server().url(LOCAL_SERVER).description("로컬 개발")))
                .components(new Components());
    }

    /**
     * 경로의 버전 자리표시자를 <strong>고객이 실제로 부르는 형태</strong>로 되돌린다.
     *
     * <p>컨트롤러 매핑은 {@code /api/{version}/orders} 다(ADR-009 — 리터럴 {@code v1} 로 두면
     * {@code /api/v2/...} 가 404 가 된다). springdoc 은 그 자리표시자를 <em>해석된 버전 값</em>으로
     * 채워 {@code /api/1/orders} 를 문서에 적는다. 그 주소로도 요청은 통하지만, 설계서(§5.1)와
     * {@code Location} 헤더가 쓰는 정식 주소는 {@code /api/v1/orders} 다.
     *
     * <p>문서가 정식 주소와 다르면 문서를 보고 만든 클라이언트가 우리가 지원한다고 말한 적 없는
     * 형태를 쓰게 된다. 그래서 문서 쪽을 정식 형태로 맞춘다.
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
