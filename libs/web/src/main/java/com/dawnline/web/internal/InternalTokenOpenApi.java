package com.dawnline.web.internal;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Arrays;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;

/**
 * 문서도 토큰을 말한다 (ADR-055 결정 3) — 토큰 대상 오퍼레이션에 보안 요구 {@value InternalToken#SECURITY_SCHEME}
 * (apiKey · header {@value InternalToken#HEADER})를 붙인다.
 *
 * <p>판정은 {@link InternalTokenInterceptor} 와 같은 두 칸(쓰기 메서드인가, 면제 표시가 있는가)이다. 둘이 어긋나지
 * 않는다는 것은 코어의 {@code OpenApiContractIT} 가 「문서가 토큰을 요구한다고 말하는 집합 = 401 을 받는 집합」으로
 * 본다.
 */
final class InternalTokenOpenApi {

    /** 오류 본문 스키마 이름 — 서비스의 다른 오류 응답이 쓰는 것과 같다({@code ProblemDetailSchema}). */
    static final String PROBLEM_DETAIL = "ProblemDetail";

    private static final Set<RequestMethod> WRITES =
            Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    private InternalTokenOpenApi() {
    }

    /** @return 토큰 대상 오퍼레이션에 보안 요구를 붙이는 customizer */
    static OperationCustomizer operations() {
        return (Operation operation, HandlerMethod handlerMethod) -> {
            if (requiresToken(handlerMethod)) {
                operation.addSecurityItem(new SecurityRequirement().addList(InternalToken.SECURITY_SCHEME));
                if (operation.getResponses() == null) {
                    operation.setResponses(new ApiResponses());
                }
                // 401 도 다른 오류와 같은 본문이다 — 문서가 그것을 말해야 「4xx 는 ProblemDetail」 검사가 이 칸도 본다.
                operation.getResponses().addApiResponse("401", new ApiResponse()
                        .description("내부 토큰(`" + InternalToken.HEADER + "`)이 없거나 다르다 — `internal-token-required`. "
                                + "운영자는 ops-api 를 거친다 (ADR-055)")
                        .content(new Content().addMediaType("*/*", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_DETAIL)))));
            }
            return operation;
        };
    }

    /** @return 문서의 {@code components.securitySchemes} 에 스킴을 싣는 customizer */
    static OpenApiCustomizer scheme() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSecuritySchemes(InternalToken.SECURITY_SCHEME, new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.HEADER)
                    .name(InternalToken.HEADER)
                    .description("코어의 운영자 쓰기 커맨드에 필요한 내부 토큰 (DESIGN.md §10 셋째 층, ADR-055). "
                            + "ops-api 가 싣는다 — 운영자는 ops-api 를 거친다."));
        };
    }

    /** 매핑에 메서드가 없으면(모든 메서드) 쓰기로 본다 — 인터셉터가 그 요청의 쓰기에 토큰을 요구하는 것과 같다. */
    private static boolean requiresToken(HandlerMethod handlerMethod) {
        if (handlerMethod.hasMethodAnnotation(UnauthenticatedWrite.class)) {
            return false;
        }
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequestMapping.class);
        if (mapping == null || mapping.method().length == 0) {
            return true;
        }
        return Arrays.stream(mapping.method()).anyMatch(WRITES::contains);
    }
}
