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

    /** 배열과 인라인이 섞인 문서. 이름 판정이 껍데기가 아니라 원소까지 내려가는지 본다. */
    private static final String MIXED = """
            {"paths": {"/api/v1/x": {"get": {"responses": {
              "200": {"content": {"application/json": {"schema":
                  {"type": "array", "items": {"$ref": "#/components/schemas/OrderView"}}}}},
              "400": {"content": {"application/json": {"schema": {"type": "object"}}}}
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
        OpenApiResponses responses = OpenApiResponses.parse(MIXED);

        assertThat(responses.all()).extracting(OpenApiResponses.Response::schema)
                .containsExactly("배열<OrderView>", "(이름 없는 object)");
        assertThat(responses.statusCodes()).containsExactly("200", "400");
    }

    @Test
    void 이름_없는_성공_본문을_집어낸다() {
        // order-service 의 POST /api/v1/orders 가 2026-09-19 까지 그랬다 —
        // ResponseEntity<Object> 라서 201·200 이 "type: object" 로 적혀 있었다. 오류 쪽 검사는
        // 2xx 를 제외하므로 이것을 보지 못한다. 그 구멍을 이 검사가 닫는다.
        String unnamed = ORDER_SHAPED.replace(
                "\"200\": {\"content\": {\"*/*\": {\"schema\": {\"$ref\": \"#/components/schemas/OrderView\"}}}}",
                "\"200\": {\"content\": {\"*/*\": {\"schema\": {\"type\": \"object\"}}}}");
        // 전제 — 표본이 실제로 바뀌었다.
        assertThat(unnamed).contains("\"type\": \"object\"");

        assertThat(OpenApiResponses.parse(unnamed).successBodiesWithoutNamedType())
                .singleElement()
                .hasToString("GET /api/v1/orders/{orderId} → 200 [*/*] (이름 없는 object)");
    }

    @Test
    void 이름_있는_성공_본문은_배열이어도_통과한다() {
        // 배열은 원소까지 내려가 본다 — 껍데기만 보면 "array" 가 이름처럼 보인다.
        assertThat(OpenApiResponses.parse(MIXED).successBodiesWithoutNamedType()).isEmpty();
        assertThat(OpenApiResponses.parse(ORDER_SHAPED).successBodiesWithoutNamedType()).isEmpty();
    }

    @Test
    void 본문을_선언하지_않은_성공_응답도_집어낸다() {
        // 204 처럼 본문이 정말 없는 2xx 가 생기면 이 검사가 실패한다 — 그때 제외를 쓰게 되고,
        // 쓰인 제외는 읽힌다 (DESIGN.md §13 규칙 2). 지금은 그런 응답이 하나도 없다.
        String noBody = """
                {"paths": {"/api/v1/x": {"post": {"responses": {"204": {"description": "없다"}}}}}}
                """;

        assertThat(OpenApiResponses.parse(noBody).successBodiesWithoutNamedType())
                .singleElement()
                .hasToString("POST /api/v1/x → 204 [(본문 없음)] (본문 없음)");
    }

    @Test
    void 성공_전제가_비면_비었다고_말한다() {
        // successBodies() 는 2xx 검사의 전제다 — errorBodies() 와 같은 이유로 둔다.
        assertThat(OpenApiResponses.parse("{}").successBodies()).isEmpty();
        assertThat(OpenApiResponses.parse(ORDER_SHAPED).successBodies())
                .extracting(OpenApiResponses.Response::status)
                .containsExactly("200");
    }

    @Test
    void springdoc_이_그리던_중첩_확장_칸을_집어낸다() {
        // 2026-09-24 까지 세 서비스의 문서가 실제로 말하던 모양이다.
        String nested = """
                {"components": {"schemas": {"ProblemDetail": {"type": "object", "properties": {
                  "type": {"type": "string"}, "status": {"type": "integer"},
                  "properties": {"type": "object", "additionalProperties": {}}}}}}}
                """;

        assertThat(OpenApiResponses.problemDetailShapeViolations(nested)).hasSize(3);
    }

    @Test
    void 확장_칸이_최상위면_어긋난_것이_없다() {
        String flat = """
                {"components": {"schemas": {"ProblemDetail": {"type": "object", "properties": {
                  "type": {"type": "string"}, "code": {"type": "string"}},
                  "additionalProperties": true}}}}
                """;

        assertThat(OpenApiResponses.problemDetailShapeViolations(flat)).isEmpty();
    }

    @Test
    void ProblemDetail_이_없는_문서를_맞다고_하지_않는다() {
        assertThat(OpenApiResponses.problemDetailShapeViolations("{\"paths\": {}}"))
                .containsExactly("문서에 ProblemDetail 스키마가 없다");
    }
}
