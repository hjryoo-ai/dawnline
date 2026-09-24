package com.dawnline.common.openapi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 생성된 OpenAPI 문서의 <strong>응답</strong>만 뽑아 보는 테스트 유틸 (DESIGN.md §14, ADR-009).
 *
 * <p>서비스마다 {@code OpenApiContractIT} 가 있고 그 검사들은 문자열 포함으로 충분했다 —
 * 「404 가 문서에 있는가」는 {@code contains("\"404\"")} 로 답할 수 있다. 그러나 <em>그 404 의
 * 본문이 무엇인가</em>는 문자열로 답할 수 없고, 그 자리에서 실제로 결함이 나왔다:
 * {@code contracts/openapi/order-service.yaml} 은 2026-09-19 까지 「404 의 본문은
 * {@code OrderView}」라고 말하고 있었다. springdoc 은 {@code @ApiResponse} 에 {@code content} 를
 * 주지 않으면 <strong>메서드 반환 타입</strong>을 모든 응답에 붙이기 때문이다.
 *
 * <p>그래서 이 클래스는 문서를 <em>구조로</em> 읽는다. YAML 이 아니라 {@code /v3/api-docs} 의
 * JSON 을 읽는 이유는 파서를 더 들이지 않기 위해서다 — 같은 문서이고, 커밋되는 YAML 은 그것의
 * 표현일 뿐이다.
 *
 * <h2>검사는 코드를 열거하지 않는다</h2>
 * 「4xx·5xx 는 Problem Details」를 확인할 때 {@code 400·404·409} 를 적어 두면 새로 생긴
 * {@code 403} 은 조용히 검사 밖에 남는다(CLAUDE.md 「집합을 도는 검사는 열거하지 않고 전체에서
 * 뺀다」). 그래서 {@link #errorBodiesNotUsing(String)} 은 <strong>2xx 가 아닌 전부</strong>를
 * 대상으로 하고, 제외한 2xx 가 왜 제외인지는 {@link #successBodiesUsing(String)} 이 말한다.
 *
 * <h2>2xx 도 계약이다</h2>
 * 오류 쪽만 보면 이 클래스는 「오류를 파싱할 수 있는가」만 답하는 검사가 된다. 같은 결함이
 * 성공 응답에도 있었다 — {@code POST /api/v1/orders} 는 {@code ResponseEntity<Object>} 를
 * 반환해서 201·200 의 본문이 {@code type: object} 로 적혀 있었고, 그것은 <em>본문이 있다고
 * 말하면서 무엇인지는 말하지 않는</em> 상태다. 그래서 {@link #successBodiesWithoutNamedType()}
 * 이 짝으로 있다: <strong>4xx 는 {@code ProblemDetail} 이고 2xx 는 이름 있는 타입이다.</strong>
 * 둘을 합쳐야 검사가 「계약이 본문을 말하는가」를 본다.
 */
public final class OpenApiResponses {

    /** OpenAPI 3 의 Path Item 에서 오퍼레이션인 키들. 나머지({@code parameters}·{@code summary})는 응답이 없다. */
    private static final Set<String> OPERATIONS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private static final Pattern SUCCESS_STATUS = Pattern.compile("2\\d\\d");

    /** 스키마 자리가 비어 있을 때 쓰는 표시. 「어떤 타입인지 문서가 말하지 않는다」는 뜻이다. */
    public static final String NO_SCHEMA = "(스키마 없음)";

    /** 응답에 {@code content} 자체가 없을 때. 본문이 없다고 <em>선언한</em> 것과 같다. */
    public static final String NO_BODY = "(본문 없음)";

    /**
     * 인라인 스키마의 표시 접두사 — {@code $ref} 가 아니라 자리에서 펼쳐진 타입이다.
     * springdoc 은 {@code ResponseEntity<Object>} 를 {@code type: object} 로 적고, 그것은
     * 「본문이 있다」와 「그 타입은 말하지 않는다」를 동시에 말한다.
     */
    public static final String UNNAMED_PREFIX = "(이름 없는 ";

    /** 배열 표시. 중첩을 풀 때 {@link #namesAType(String)} 이 같은 문자열을 쓴다. */
    private static final String ARRAY_PREFIX = "배열<";

    private final List<Response> responses;

    /**
     * 응답 하나 — 미디어 타입까지 내려간 단위다. 하나의 상태 코드가 여러 미디어 타입을 가질 수
     * 있고, 그중 하나만 어긋나도 그 클라이언트는 깨진다.
     *
     * @param method    HTTP 메서드 (대문자)
     * @param path      경로 템플릿
     * @param status    상태 코드. {@code default} 일 수 있다
     * @param mediaType 미디어 타입
     * @param schema    스키마 이름. {@code $ref} 면 마지막 세그먼트, 배열이면 {@code 배열<X>}
     */
    public record Response(String method, String path, String status, String mediaType, String schema) {

        /** 2xx 인가. {@code default} 는 아니다 — 문서에서 그것은 「그 밖의 모든 경우」다. */
        public boolean isSuccess() {
            return SUCCESS_STATUS.matcher(status).matches();
        }

        @Override
        public String toString() {
            return "%s %s → %s [%s] %s".formatted(method, path, status, mediaType, schema);
        }
    }

    private OpenApiResponses(List<Response> responses) {
        this.responses = List.copyOf(responses);
    }

    /**
     * {@code /v3/api-docs} 의 JSON 을 읽는다.
     *
     * @param apiDocsJson springdoc 이 낸 문서
     * @return 응답 목록
     */
    public static OpenApiResponses parse(String apiDocsJson) {
        Objects.requireNonNull(apiDocsJson, "apiDocsJson");
        JsonNode paths = JsonMapper.builder().build().readTree(apiDocsJson).path("paths");

        List<Response> found = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                if (!OPERATIONS.contains(operation.getKey())) {
                    continue;
                }
                collectResponses(found, path.getKey(), operation.getKey(), operation.getValue());
            }
        }
        found.sort(Comparator.comparing(Response::path)
                .thenComparing(Response::method)
                .thenComparing(Response::status)
                .thenComparing(Response::mediaType));
        return new OpenApiResponses(found);
    }

    private static void collectResponses(List<Response> into, String path, String method,
            JsonNode operation) {

        String upper = method.toUpperCase(java.util.Locale.ROOT);
        for (Map.Entry<String, JsonNode> response : operation.path("responses").properties()) {
            JsonNode content = response.getValue().path("content");
            if (content.isMissingNode() || content.isEmpty()) {
                into.add(new Response(upper, path, response.getKey(), NO_BODY, NO_BODY));
                continue;
            }
            for (Map.Entry<String, JsonNode> media : content.properties()) {
                into.add(new Response(upper, path, response.getKey(), media.getKey(),
                        schemaNameOf(media.getValue().path("schema"))));
            }
        }
    }

    private static String schemaNameOf(JsonNode schema) {
        if (schema.isMissingNode() || schema.isEmpty()) {
            return NO_SCHEMA;
        }
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asString();
            return ref.substring(ref.lastIndexOf('/') + 1);
        }
        if (schema.has("items")) {
            return ARRAY_PREFIX + schemaNameOf(schema.get("items")) + ">";
        }
        // 인라인 스키마. 이름이 없다는 것 자체가 결함일 수 있으므로(ResponseEntity<Object> 의
        // "type: object") 타입을 보여 주되 이름이 아니라는 표시를 함께 단다.
        return schema.path("type").isMissingNode()
                ? NO_SCHEMA
                : UNNAMED_PREFIX + schema.get("type").asString() + ")";
    }

    /**
     * 스키마 이름이 <strong>이름 있는 타입</strong>인가 — {@code $ref} 에서 온 이름이거나 그
     * 배열인가. 배열은 원소까지 내려가 본다: {@code 배열<(이름 없는 object)>} 는 이름이 아니다.
     *
     * <p>판정을 파싱 시점의 구조에서 끌어오는 것이 요점이다. {@code object}·{@code string} 같은
     * 기본 타입 이름을 <em>목록으로</em> 두고 비교하면 그 목록이 곧 열거가 되고, 스펙이 타입을
     * 더하면 조용히 검사 밖에 남는다.
     *
     * @param schema {@link Response#schema()}
     * @return 이름 있는 타입이면 {@code true}
     */
    public static boolean namesAType(String schema) {
        Objects.requireNonNull(schema, "schema");
        String inner = schema;
        while (inner.startsWith(ARRAY_PREFIX) && inner.endsWith(">")) {
            inner = inner.substring(ARRAY_PREFIX.length(), inner.length() - 1);
        }
        return !inner.startsWith(UNNAMED_PREFIX) && !NO_SCHEMA.equals(inner) && !NO_BODY.equals(inner);
    }

    /** 문서의 모든 응답. */
    public List<Response> all() {
        return responses;
    }

    /**
     * 2xx 가 아닌 응답 전부. 검사의 <strong>전제</strong>를 말하는 데 쓴다 — 이것이 비어 있으면
     * 아래 검사들은 통과하면서 아무것도 보지 않는다.
     *
     * @return 오류 응답들
     */
    public List<Response> errorBodies() {
        return responses.stream().filter(response -> !response.isSuccess()).toList();
    }

    /**
     * 2xx 가 아닌데 주어진 스키마를 쓰지 <em>않는</em> 응답들.
     *
     * @param schemaName 기대하는 스키마 이름 (예: {@code ProblemDetail})
     * @return 어긋난 응답들. 비어 있어야 한다
     */
    public List<Response> errorBodiesNotUsing(String schemaName) {
        Objects.requireNonNull(schemaName, "schemaName");
        return errorBodies().stream()
                .filter(response -> !schemaName.equals(response.schema()))
                .toList();
    }

    /**
     * 2xx 인 응답 전부. {@link #errorBodies()} 와 같은 이유로 둔다 — 아래 2xx 검사의
     * <strong>전제</strong>다.
     *
     * @return 성공 응답들
     */
    public List<Response> successBodies() {
        return responses.stream().filter(Response::isSuccess).toList();
    }

    /**
     * 2xx 인데 <strong>이름 있는 타입</strong>을 말하지 않는 응답들 —
     * {@link #errorBodiesNotUsing(String)} 의 짝이다.
     *
     * <p>세 가지가 걸린다: 인라인 타입({@code type: object}), 스키마가 빈 자리, 그리고 본문
     * 선언이 아예 없는 응답. 셋 다 「읽는 쪽이 각자 다르게 가정하게 되는」 같은 상태다.
     *
     * <p><strong>본문이 정말 없는 2xx</strong>(예: {@code 204})가 생기면 이 검사는 실패한다.
     * 그것이 의도다 — 그때 제외를 <em>쓰게</em> 되고, 쓰인 제외는 읽힌다(DESIGN.md §13 규칙 2).
     * 지금 이 저장소에는 본문 없는 2xx 가 하나도 없으므로, 없는 구성원을 위해 미리 제외를
     * 적어 두지 않는다.
     *
     * @return 타입을 말하지 않는 성공 응답들. 비어 있어야 한다
     */
    public List<Response> successBodiesWithoutNamedType() {
        return successBodies().stream()
                .filter(response -> !namesAType(response.schema()))
                .toList();
    }

    /**
     * 2xx 인데 주어진 스키마를 쓰는 응답들 — {@link #errorBodiesNotUsing(String)} 이 2xx 를
     * <em>왜</em> 제외했는지를 말하는 검사다. 성공 본문은 유스케이스의 반환 타입이어야 하고,
     * 거기에 Problem Details 가 나타나면 그것은 오류를 200 으로 싣고 있다는 뜻이다.
     *
     * @param schemaName 오류 본문의 스키마 이름
     * @return 성공 응답인데 오류 본문인 것들. 비어 있어야 한다
     */
    public List<Response> successBodiesUsing(String schemaName) {
        Objects.requireNonNull(schemaName, "schemaName");
        return responses.stream()
                .filter(Response::isSuccess)
                .filter(response -> schemaName.equals(response.schema()))
                .toList();
    }

    /** 문서에 나타난 상태 코드 전부 — 실패 메시지를 읽을 때 범위를 가늠하는 용도. */
    public Set<String> statusCodes() {
        return responses.stream()
                .map(Response::status)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 문서의 {@code ProblemDetail} 이 <strong>실제 본문의 모양</strong>인가 — 확장 멤버가 최상위에 있는가.
     *
     * <p>본문 쪽 사실은 서비스 IT 가 {@code jsonPath("$.code")} 로 본다(tracking 의 {@code ScanApiIT}).
     * 이 메서드는 같은 사실을 <em>문서</em> 쪽에서 대조한다. springdoc 은 확장 멤버 맵을 {@code properties}
     * 라는 중첩 객체로 그렸고, 그 문서로 만든 클라이언트(ops-api)는 {@code code} 를 잃었다(ADR-052).
     *
     * @param apiDocsJson springdoc 이 낸 문서
     * @return 어긋난 점. 비어 있어야 한다. 문서에 {@code ProblemDetail} 이 없으면 그 사실 하나를 돌려준다 —
     *         없는 스키마를 「맞다」고 하지 않는다
     */
    public static List<String> problemDetailShapeViolations(String apiDocsJson) {
        Objects.requireNonNull(apiDocsJson, "apiDocsJson");
        JsonNode problem = JsonMapper.builder().build().readTree(apiDocsJson)
                .path("components").path("schemas").path("ProblemDetail");
        if (problem.isMissingNode()) {
            return List.of("문서에 ProblemDetail 스키마가 없다");
        }
        List<String> violations = new ArrayList<>();
        JsonNode properties = problem.path("properties");
        if (properties.has("properties")) {
            violations.add("확장 멤버가 중첩 객체 `properties` 로 그려져 있다 — 실제 본문은 최상위로 펼친다");
        }
        if (!properties.has("code")) {
            violations.add("`code` 가 최상위 칸에 없다 — 모든 오류에 실리는 확장 멤버다");
        }
        JsonNode additional = problem.path("additionalProperties");
        if (!(additional.isBoolean() && additional.booleanValue()) && !additional.isObject()) {
            violations.add("추가 칸을 허용하지 않는다 — `retryAfterSeconds`·검증 오류 같은 확장 멤버가 문서 밖이 된다");
        }
        return violations;
    }
}
