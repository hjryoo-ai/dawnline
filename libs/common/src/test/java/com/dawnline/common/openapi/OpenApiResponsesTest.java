package com.dawnline.common.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@link OpenApiResponses} 자체의 검사.
 *
 * <p>이 픽스처가 조용히 <em>빈 목록</em>을 내면 그것을 쓰는 모든 {@code OpenApiContractIT} 가
 * 통과하면서 아무것도 보지 않는다. 그래서 「찾아야 할 것을 찾는가」와 「전제가 무너진 문서를
 * 어떻게 말하는가」를 둘 다 고정한다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("OpenApiResponses — 문서를 구조로 읽는다")
class OpenApiResponsesTest {

    /** order-service.yaml 이 2026-09-19 까지 실제로 말하던 모양이다. */
    private static final String ORDER_SHAPED = """
            {"paths": {"/api/v1/orders/{orderId}": {
              "parameters": [{"name": "orderId"}],
              "get": {"responses": {
                "200": {"content": {"*/*": {"schema": {"$ref": "#/components/schemas/OrderView"}}}},
                "404": {"content": {"*/*": {"schema": {"$ref": "#/components/schemas/OrderView"}}}}
              }}}}}
            """;

    @Test
    void 오류_응답이_반환_타입을_쓰고_있으면_그것을_집어낸다() {
        OpenApiResponses responses = OpenApiResponses.parse(ORDER_SHAPED);

        assertThat(responses.errorBodiesNotUsing("ProblemDetail"))
                .singleElement()
                .hasToString("GET /api/v1/orders/{orderId} → 404 [*/*] OrderView");
    }

    @Test
    void 성공_응답은_오류_검사에서_빠진다() {
        OpenApiResponses responses = OpenApiResponses.parse(ORDER_SHAPED);

        assertThat(responses.errorBodies()).extracting(OpenApiResponses.Response::status)
                .containsExactly("404");
        assertThat(responses.all()).hasSize(2);
    }

    @Test
    void 오류_본문이_Problem_Details_면_어긋난_것이_없다() {
        String fixed = ORDER_SHAPED.replace(
                "\"404\": {\"content\": {\"*/*\": {\"schema\": {\"$ref\": \"#/components/schemas/OrderView\"}}}}",
                "\"404\": {\"content\": {\"*/*\": {\"schema\": {\"$ref\": \"#/components/schemas/ProblemDetail\"}}}}");
        // 전제 — 표본이 실제로 바뀌었다. 안 바뀌면 아래 어설션은 원본을 통과시키는 셈이 된다.
        assertThat(fixed).doesNotContain("OrderView\"}}}}\n              }}");

        assertThat(OpenApiResponses.parse(fixed).errorBodiesNotUsing("ProblemDetail")).isEmpty();
    }

    @Test
    void 성공_응답에_오류_본문이_실려_있으면_집어낸다() {
        // 오류를 200 으로 싣는 형태다. 2xx 를 제외한 것이 「검토했는데 제외」임을 이 검사가 말한다.
        String wrong = ORDER_SHAPED.replace("schemas/OrderView\"}}}},", "schemas/ProblemDetail\"}}}},");

        assertThat(OpenApiResponses.parse(wrong).successBodiesUsing("ProblemDetail"))
                .singleElement()
                .hasToString("GET /api/v1/orders/{orderId} → 200 [*/*] ProblemDetail");
    }

    @Test
    void 본문이_선언되지_않은_응답도_오류_목록에_남는다() {
        // "스키마가 ProblemDetail 이다" 를 만족하지 않는 또 하나의 방식이다 — 문서가 본문에
        // 대해 아무 말도 하지 않으면, 읽는 쪽은 각자 다르게 가정한다.
        String noBody = """
                {"paths": {"/api/v1/x": {"post": {"responses": {"503": {"description": "없다"}}}}}}
                """;

        assertThat(OpenApiResponses.parse(noBody).errorBodiesNotUsing("ProblemDetail"))
                .singleElement()
                .hasToString("POST /api/v1/x → 503 [(본문 없음)] (본문 없음)");
    }

    @Test
    void 경로가_없는_문서는_빈_목록이다() {
        // 이것이 서비스 IT 들이 전제 어설션(errorBodies().isNotEmpty())을 두는 이유다 —
        // 문서 생성이 통째로 망가져도 "어긋난 것 없음" 은 초록이다.
        assertThat(OpenApiResponses.parse("{}").errorBodies()).isEmpty();
        assertThat(OpenApiResponses.parse("{}").all()).isEmpty();
    }

    @Test
    void 배열과_인라인_스키마도_이름으로_읽는다() {
        String mixed = """
                {"paths": {"/api/v1/x": {"get": {"responses": {
                  "200": {"content": {"application/json": {"schema":
                      {"type": "array", "items": {"$ref": "#/components/schemas/OrderView"}}}}},
                  "400": {"content": {"application/json": {"schema": {"type": "object"}}}}
                }}}}}
                """;
        OpenApiResponses responses = OpenApiResponses.parse(mixed);

        assertThat(responses.all()).extracting(OpenApiResponses.Response::schema)
                .containsExactly("배열<OrderView>", "object");
        assertThat(responses.statusCodes()).containsExactly("200", "400");
    }
}
