package com.dawnline.web.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

/** 문서의 보안 요구는 인터셉터와 같은 판정이다 (ADR-055 결정 3). */
class InternalTokenOpenApiTest {

    private final InternalTokenInterceptorTest.Things things = new InternalTokenInterceptorTest.Things();

    @Test
    void 토큰_대상_쓰기에만_보안_요구가_붙는다() throws Exception {
        assertThat(securityOf("close")).containsExactly(InternalToken.SECURITY_SCHEME);
        assertThat(securityOf("replace", String.class)).containsExactly(InternalToken.SECURITY_SCHEME);
        assertThat(securityOf("remove", String.class)).containsExactly(InternalToken.SECURITY_SCHEME);
        assertThat(securityOf("place")).as("면제 표시").isEmpty();
        assertThat(securityOf("read", String.class)).as("읽기").isEmpty();
    }

    @Test
    void 토큰_대상_쓰기의_401_은_ProblemDetail_이다() throws Exception {
        Operation operation = InternalTokenOpenApi.operations().customize(new Operation(), new HandlerMethod(things,
                InternalTokenInterceptorTest.Things.class.getDeclaredMethod("close")));

        assertThat(operation.getResponses().get("401").getContent().get("*/*").getSchema().get$ref())
                .isEqualTo("#/components/schemas/ProblemDetail");
    }

    @Test
    void 스킴은_헤더_apiKey_다() {
        OpenAPI openApi = new OpenAPI();
        InternalTokenOpenApi.scheme().customise(openApi);

        SecurityScheme scheme = openApi.getComponents().getSecuritySchemes().get(InternalToken.SECURITY_SCHEME);
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
        assertThat(scheme.getIn()).isEqualTo(SecurityScheme.In.HEADER);
        assertThat(scheme.getName()).isEqualTo(InternalToken.HEADER);
    }

    private List<String> securityOf(String method, Class<?>... parameterTypes) throws Exception {
        HandlerMethod handler = new HandlerMethod(things,
                InternalTokenInterceptorTest.Things.class.getDeclaredMethod(method, parameterTypes));
        Operation operation = InternalTokenOpenApi.operations().customize(new Operation(), handler);
        return operation.getSecurity() == null ? List.of()
                : operation.getSecurity().stream().flatMap(requirement -> requirement.keySet().stream()).toList();
    }
}
