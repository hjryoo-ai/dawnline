package com.dawnline.ops.config;

import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 문서 (DESIGN.md §5.5 · §11 — {@code contracts/openapi/ops-api.yaml}).
 *
 * <p>생성된 문서는 저장소에 커밋되고 {@code OpenApiContractIT} 가 코드와 어긋나지 않는지 검사한다.
 *
 * <p><strong>왜 지금인가</strong>: ops-api 는 묶음 B 부터 REST 표면이 있었지만 문서의 소비자가 없었다. 첫 소비자는
 * ops-web 의 TS 클라이언트다(묶음 C) — 백엔드 넷이 코어 문서로 위임 클라이언트를 만든 것과 같은 규칙이고, 프론트만
 * 손으로 쓴 타입이면 그 규칙의 예외가 된다. 부재는 첫 소비자가 나타나는 시점에 채운다(§11).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /** 보안 스킴 이름 — 모든 {@code /api} 오퍼레이션이 요구한다. */
    static final String SECURITY_SCHEME = "opsJwt";

    /** 오류 본문 스키마 이름 — 다른 오류 응답이 쓰는 것과 같다({@code ProblemDetailSchema}). */
    private static final String PROBLEM_DETAIL = "#/components/schemas/ProblemDetail";

    /** 문서에 적히는 API 버전. 경로 세그먼트 {@code v1} 과 같은 major 다 (ADR-009). */
    private static final String API_VERSION = "1.0.0";

    /** 로컬 기본 포트 (application.yml 의 {@code server.port}). */
    private static final String LOCAL_SERVER = "http://localhost:8085";

    /** springdoc 이 {@code {version}} 자리표시자를 채운 결과. */
    private static final String RESOLVED_VERSION_PREFIX = "/api/1/";

    /** 설계서가 쓰는 정식 형태. */
    private static final String CANONICAL_VERSION_PREFIX = "/api/v1/";

    /** OpenAPI 문서의 머리말. */
    @Bean
    public OpenAPI opsApiOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Dawnline ops-api")
                        .version(API_VERSION)
                        .description("""
                                운영 콘솔 API (DESIGN.md §5.5). 읽기 모델 조회와 운영자 커맨드다.

                                **인증은 JWT 다.** `make token ROLE=…` 가 찍은 토큰을 `Authorization: Bearer` 로 싣는다 \
                                (만료 12시간). `GET` 은 `OPS_VIEWER`, 나머지는 `OPS_OPERATOR` 이상이다. 토큰이 없거나 \
                                유효하지 않으면 401 `unauthenticated`, 역할이 모자라면 403 `forbidden` 이다.

                                **커맨드는 코어로 위임하고 감사한다.** 응답 헤더 `X-Dawnline-Audit-Id` 에 감사 행 id 가 온다. \
                                코어가 거절하면(4xx) 코어의 상태와 Problem Details 본문을 그대로 돌려준다. 코어에 닿지 못하면 \
                                502 `core-unreachable`, 적용됐는지 모르면 504 `core-timeout` 또는 502 `core-error` 다 — 그때는 \
                                다시 누르기가 먼저다(RB-07).

                                **조회는 감사하지 않는다.** 읽기 모델(`rm_*`)에서 읽고, 라우트의 stop 은 dispatch 에 조회를 \
                                위임한다 — 좌표와 순서의 진실은 dispatch 이고 읽기 모델은 집계다.

                                오류 응답은 RFC 9457 Problem Details 이고 `type` 과 `code` 가 항상 채워진다."""))
                .servers(List.of(new Server().url(LOCAL_SERVER).description("로컬 개발")))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("`make token ROLE=OPS_VIEWER|OPS_OPERATOR|ADMIN` 가 찍는 HS256 토큰 (DESIGN.md §5.5)")));
    }

    /**
     * 모든 오퍼레이션이 토큰을 요구하고, 401·403 을 받을 수 있다 — 보안 설정이 경로를 열거하지 않으므로(메서드로 가른다)
     * 문서도 열거하지 않는다. 두 본문은 다른 오류와 같은 Problem Details 다({@link OpsAuthErrorCode}).
     */
    @Bean
    public OperationCustomizer opsJwtOperations() {
        return (operation, handlerMethod) -> {
            operation.addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
            if (operation.getResponses() == null) {
                operation.setResponses(new ApiResponses());
            }
            operation.getResponses()
                    .addApiResponse("401", problem("토큰이 없거나 유효하지 않다 — `unauthenticated`. 토큰을 다시 넣는다"))
                    .addApiResponse("403", problem("역할이 모자란다 — `forbidden`. 조회는 `OPS_VIEWER`, 커맨드는 `OPS_OPERATOR` 이상"));
            return operation;
        };
    }

    /**
     * 경로의 버전 자리표시자를 <strong>호출자가 실제로 부르는 형태</strong>로 되돌린다 (코어의 같은 이름 customizer 와
     * 같은 이유 — ADR-009).
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

    /**
     * 레코드의 널 가능성(JSpecify)을 문서의 {@code required} 로 옮긴다 — 문서의 소비자(ops-web 의 타입)가 코드보다
     * 약하게 말하지 않게. springdoc 은 {@code ModelConverter} 빈을 스키마 해석 사슬에 넣는다.
     *
     * @return 변환기
     */
    @Bean
    public ModelConverter nullabilityRequiredConverter() {
        return new NullabilityRequiredConverter();
    }

    private static ApiResponse problem(String description) {
        return new ApiResponse().description(description)
                .content(new Content().addMediaType("*/*", new MediaType().schema(new Schema<>().$ref(PROBLEM_DETAIL))));
    }
}
