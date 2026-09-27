package com.dawnline.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dawnline.common.openapi.OpenApiResponses;
import com.dawnline.observability.docs.AlertedCountersContract;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAPI 문서와 코드의 일치 (DESIGN.md §5.5 · §11 — Phase 6 묶음 C).
 *
 * <p>{@code contracts/openapi/ops-api.yaml} 은 <strong>생성물</strong>이다. 손으로 고치면 이 테스트가 깨진다.
 *
 * <p>이 문서의 소비자는 <strong>ops-web</strong> 이다 — TS 클라이언트가 이 문서에서 빌드 때 만들어진다(묶음 C 의 다음 PR, ADR-056). 백엔드
 * 넷이 코어 문서로 위임 클라이언트를 만든 것과 같은 규칙이고, 프론트만 손으로 쓴 타입이면 그 규칙의 예외가 된다.
 *
 * <p>코어의 같은 이름 IT 와 달리 {@code InternalTokenSurfaceContract} 를 구현하지 않는다 — ops-api 는 코어가 아니고
 * 내부 토큰을 요구하지 않는다(ADR-055, {@code enforce=false}). 대신 모든 오퍼레이션이 JWT 를 요구한다는 것을 본다.
 *
 * <p><strong>다시 만들려면</strong>: {@code ./gradlew :services:ops-api:updateOpenApi}.
 */
@SpringBootTest(classes = OpsApplication.class)
@AutoConfigureMockMvc
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("OpenApiContractIT — 문서가 코드와 어긋나지 않는다")
class OpenApiContractIT extends OpsIntegrationTestBase implements AlertedCountersContract {

    /**
     * 기동한 레지스트리 — 알림 걸린 닫힌 카운터의 조합이 기동 때 전부 있는지를 이 컨텍스트에서 본다
     * ({@link AlertedCountersContract}, ADR-060 결정 3). 컨텍스트를 새로 띄우지 않으려고 이 IT 에 둔다.
     */
    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Override
    public io.micrometer.core.instrument.MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    @Override
    public String emitter() {
        return "ops-api";
    }

    /** 저장소에 커밋되는 문서. */
    private static final Path CONTRACT = Path.of("../../contracts/openapi/ops-api.yaml");

    /** 이 값을 주면 문서를 다시 쓴다. */
    private static final String UPDATE_FLAG = "dawnline.openapi.update";

    /** 오류 본문의 스키마 이름 (RFC 9457). */
    private static final String PROBLEM_DETAIL = "ProblemDetail";

    /** 문서도 뷰어의 것이다 — {@code GET} 은 {@code OPS_VIEWER}. */
    private static final String VIEWER = "Bearer " + OpsTokens.token("OPS_VIEWER", "it-openapi-contract");

    @Autowired
    private MockMvc mockMvc;

    /**
     * 이 IT 는 문서만 읽는다 — 브로커·발행을 보지 않는다. 공유 자원은 자기 자리에서 끈다(CLAUDE.md).
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void 공유_자원을_끈다(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Test
    void springdoc_이_ops_api_의_문서를_만든다() throws Exception {
        assertThat(generatedYaml()).contains("openapi: 3.").contains("Dawnline ops-api");
    }

    @Test
    void 설계서_5_5_의_엔드포인트가_모두_문서에_있다() throws Exception {
        assertThat(generatedYaml())
                // 커맨드 (§5.5 「커맨드 위임」)
                .contains("/api/v1/plans/{waveId}/run:")
                .contains("/api/v1/routes/{routeId}/stops/{orderId}/reassign:")
                .contains("/api/v1/orders/{orderId}/cancel:")
                .contains("/api/v1/waves/{waveId}/close:")
                .contains("/api/v1/admin/outbox/{service}/quarantined:")
                .contains("/api/v1/admin/outbox/{service}/{id}/requeue:")
                .contains("/api/v1/admin/dlq/{topic}:")
                .contains("/api/v1/admin/dlq/{topic}/replay:")
                // 조회 (§5.5 「조회」, 묶음 C)
                .contains("/api/v1/camps:")
                .contains("/api/v1/camps/{campId}/waves:")
                .contains("/api/v1/camps/{campId}/exceptions:")
                .contains("/api/v1/kpi/delivery:")
                .contains("/api/v1/waves/{waveId}/routes:")
                .contains("/api/v1/routes/{routeId}:");
    }

    @Test
    void 문서의_주소가_정식_형태다() throws Exception {
        assertThat(generatedYaml()).doesNotContain("/api/1/").doesNotContain("{version}");
    }

    @Test
    void 모든_오퍼레이션이_JWT_를_요구하고_401_403_을_말한다() throws Exception {
        // 보안 설정은 경로를 열거하지 않는다(메서드로 가른다). 문서도 열거하지 않는다 — 문서의 오퍼레이션 **전부**다.
        JsonNode paths = JsonMapper.builder().build().readTree(generatedJson()).path("paths");
        List<String> operations = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                String name = operation.getKey().toUpperCase(java.util.Locale.ROOT) + " " + path.getKey();
                operations.add(name);
                JsonNode body = operation.getValue();
                if (!body.path("security").toString().contains("opsJwt")) {
                    violations.add(name + " — security 에 opsJwt 가 없다");
                }
                for (String code : List.of("401", "403")) {
                    if (body.path("responses").path(code).isMissingNode()) {
                        violations.add(name + " — " + code + " 가 없다");
                    }
                }
            }
        }

        assertThat(operations).as("전제 — 문서에 오퍼레이션이 있다").isNotEmpty();
        assertThat(violations).isEmpty();
    }

    @Test
    void ProblemDetail_의_확장_칸은_최상위다() throws Exception {
        assertThat(OpenApiResponses.problemDetailShapeViolations(generatedJson())).isEmpty();
    }

    @Test
    void 오류_응답의_본문은_모두_Problem_Details_다() throws Exception {
        // 2xx 가 아닌 **전부**다 — 필터가 내는 401·403 도, 코어의 거절을 옮기는 4xx 도 같은 본문이다.
        OpenApiResponses responses = OpenApiResponses.parse(generatedJson());

        assertThat(responses.errorBodies()).as("전제 — 문서에 2xx 아닌 응답이 있다").isNotEmpty();
        assertThat(responses.errorBodiesNotUsing(PROBLEM_DETAIL)).isEmpty();
    }

    @Test
    void 성공_응답에는_오류_본문이_실리지_않는다() throws Exception {
        assertThat(OpenApiResponses.parse(generatedJson()).successBodiesUsing(PROBLEM_DETAIL)).isEmpty();
    }

    @Test
    void 성공_응답의_본문은_이름_있는_타입이다() throws Exception {
        // 커맨드 컨트롤러는 ResponseEntity<?> 를 돌려준다 — 적어 주지 않으면 springdoc 은 본문을 `type: object` 로
        // 적고, 이 문서로 만든 ops-web 의 클라이언트는 응답 타입을 모른다. 그래서 @ApiResponse 가 CoreReply 의
        // 레코드를 이름으로 가리킨다.
        OpenApiResponses responses = OpenApiResponses.parse(generatedJson());

        assertThat(responses.successBodies()).as("전제 — 문서에 2xx 응답이 있다").isNotEmpty();
        assertThat(responses.successBodiesWithoutNamedType()).isEmpty();
    }

    @Test
    void 커맨드의_성공_응답은_감사_id_헤더를_말한다() throws Exception {
        // 조회에는 없다(감사하지 않는다) — 그 차이가 문서에 있어야 화면이 「결과를 모를 때 무엇을 보라」고 말할 수 있다.
        JsonNode paths = JsonMapper.builder().build().readTree(generatedJson()).path("paths");
        Set<String> commands = Set.of("/api/v1/plans/{waveId}/run", "/api/v1/routes/{routeId}/stops/{orderId}/reassign",
                "/api/v1/orders/{orderId}/cancel", "/api/v1/waves/{waveId}/close",
                "/api/v1/admin/outbox/{service}/{id}/requeue");
        for (String command : commands) {
            assertThat(paths.path(command).path("post").path("responses").path("200").path("headers")
                    .has("X-Dawnline-Audit-Id")).as(command).isTrue();
        }
    }

    @Test
    void 레코드의_널_가능성이_문서의_required_로_옮겨진다() throws Exception {
        // springdoc 은 JSpecify 를 모른다 — 그대로 두면 모든 칸이 선택이고, ops-web 의 타입이 코드보다 약하게 말한다
        // (NullabilityRequiredConverter). 한 스키마에서 양쪽을 본다: @Nullable 이 없는 칸은 필수, 있는 칸은 선택.
        JsonNode schemas = JsonMapper.builder().build().readTree(generatedJson()).path("components").path("schemas");

        assertThat(texts(schemas.path("WaveRoutes").path("required"))).containsExactlyInAnyOrder("waveId", "routes");
        assertThat(texts(schemas.path("CampKpi").path("required")))
                .contains("campId", "delivered", "failed")
                .doesNotContain("onTimeRatioPromised", "onTimeRatioRevised");
        assertThat(texts(schemas.path("RouteStop").path("required")))
                .containsExactlyInAnyOrder("seq", "lat", "lng", "plannedArrival", "status", "orderIds");
    }

    @Test
    void Nullable_칸은_null_을_허용하고_검증이_필수로_만든_칸은_허용하지_않는다() throws Exception {
        // 본문은 @Nullable 칸을 빼지 않고 null 을 싣는다(ReadSurfaceIT 가 본문 쪽에서 본다). 「선택」만 적으면 TS 타입이
        // undefined 만 알고 실제로 오는 null 을 모른다 — ADR-056 시도에서 드러난 문서의 거짓.
        JsonNode schemas = JsonMapper.builder().build().readTree(generatedJson()).path("components").path("schemas");
        JsonNode waveRoutes = schemas.path("WaveRoutes").path("properties");

        assertThat(texts(waveRoutes.path("planId").path("type"))).containsExactlyInAnyOrder("string", "null");
        assertThat(waveRoutes.path("depot").path("anyOf").findValuesAsString("type")).as("참조는 anyOf 로 감싼다")
                .containsExactly("null");
        assertThat(waveRoutes.path("depot").path("anyOf").findValuesAsString("$ref"))
                .containsExactly("#/components/schemas/Depot");
        assertThat(typesOf(waveRoutes.path("waveId"))).doesNotContain("null");
        // 요청 본문의 @Nullable @NotBlank — null 은 서버가 400 으로 돌려보낸다. 문서가 그보다 약하게 말하지 않는다.
        assertThat(texts(schemas.path("CloseBody").path("required"))).contains("reason");
        assertThat(typesOf(schemas.path("CloseBody").path("properties").path("reason"))).doesNotContain("null");
        assertThat(typesOf(schemas.path("ReassignBody").path("properties").path("targetRouteId")))
                .doesNotContain("null");
    }

    /** 위임 응답의 가리킴 — 설명이 이것으로 <strong>끝난다</strong>(ADR-052 후속, DESIGN.md §5.5). */
    private static final Pattern POINTER =
            Pattern.compile("코어의 거절 그대로 — 사유는 `([a-z{}-]+\\.yaml)` 의 `([A-Za-z0-9_]+)`$");

    /** 설명 안의 오류 코드 — 백틱 안의 kebab-case. */
    private static final Pattern ERROR_CODE = Pattern.compile("`([a-z][a-z0-9]*(?:-[a-z0-9]+)+)`");

    /** {@code {service}} 가 가리키는 코어 — 경로 변수의 값 집합(§5.5 「outbox 경로에만 {service} 한 칸」). */
    private static final List<String> CORES = List.of("order", "fulfillment", "dispatch", "tracking");

    @Test
    void 위임_응답은_코어의_사유를_재진술하지_않고_코어_문서를_가리킨다() throws Exception {
        // 재배정 409 의 설명이 「끝난 stop 등」을 약속했고 코어는 그것을 거절하지 않았다 — 재진술은 대조할 짝이 없어 갈라져도
        // 조용했다(§13 축 17). 위임 오퍼레이션은 열거하지 않고 문서에서 뺀다: 코어에 닿지 못할 수 있는 것(502)이 위임이다.
        JsonNode paths = JsonMapper.builder().build().readTree(generatedJson()).path("paths");
        List<String> delegated = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                JsonNode responses = operation.getValue().path("responses");
                if (!responses.has("502")) {
                    continue;
                }
                String name = operation.getKey().toUpperCase(java.util.Locale.ROOT) + " " + path.getKey();
                delegated.add(name);
                Set<String> targets = new java.util.TreeSet<>();
                Set<String> pointed = new java.util.TreeSet<>();
                for (Map.Entry<String, JsonNode> response : responses.properties()) {
                    String status = response.getKey();
                    if (!status.startsWith("4") || status.equals("401") || status.equals("403")) {
                        continue;
                    }
                    String description = response.getValue().path("description").asString();
                    Matcher pointer = POINTER.matcher(description);
                    if (!pointer.find()) {
                        if (description.contains("코어")) {
                            violations.add(name + " " + status + " — 코어의 거절을 말하면서 코어 문서를 가리키지 않는다: " + description);
                        }
                        continue;
                    }
                    targets.add(pointer.group(1) + " " + pointer.group(2));
                    pointed.add(status);
                    List<Set<String>> core = coreResponses(pointer.group(1), pointer.group(2));
                    if (core == null) {
                        violations.add(name + " " + status + " — 가리킴이 풀리지 않는다: " + pointer.group(1) + " " + pointer.group(2));
                    } else if (core.stream().anyMatch(codes -> !codes.contains(status))) {
                        violations.add(name + " " + status + " — 가리킨 코어 오퍼레이션이 " + status + " 를 말하지 않는다");
                    }
                }
                if (targets.size() > 1) {
                    violations.add(name + " — 한 오퍼레이션이 코어 오퍼레이션 둘 이상을 가리킨다: " + targets);
                }
                // 빠진 것 — 코어가 말하는 404 · 409 는 운영자에게도 온다. 400 은 뺀다: ops-api 가 같은 입력을 먼저 검증해 코어의
                // 400 에 닿지 않는 자리가 있다(경로의 UUID 형식).
                List<Set<String>> core = targets.isEmpty() ? samePathInCores(name)
                        : coreResponses(targets.iterator().next().split(" ")[0], targets.iterator().next().split(" ")[1]);
                for (Set<String> codes : core == null ? List.<Set<String>>of() : core) {
                    for (String status : List.of("404", "409")) {
                        if (codes.contains(status) && !pointed.contains(status)) {
                            violations.add(name + " " + status + " — 코어가 말하는데 가리키는 응답이 없다");
                        }
                    }
                }
            }
        }

        assertThat(delegated).as("전제 — 위임 오퍼레이션을 문서에서 읽었다")
                .contains("POST /api/v1/routes/{routeId}/stops/{orderId}/reassign", "POST /api/v1/waves/{waveId}/close")
                .hasSizeGreaterThan(5);
        assertThat(violations).isEmpty();
    }

    @Test
    void 응답_설명에_ops_api_가_내지_않는_오류_코드가_없다() throws Exception {
        // ops-api 가 내는 코드는 ErrorCode 구현에서 읽는다(열거하지 않는다). 그 밖의 코드는 코어의 것이고, 코어의 것은 코어
        // 문서가 말한다 — 여기 적으면 재진술이다(ADR-052 후속).
        Set<String> own = ownErrorCodes();
        assertThat(own).as("전제 — ops-api 의 코드를 코드에서 읽었다")
                .contains("core-timeout", "unauthenticated", "audit-already-resolved", "validation-failed");

        JsonNode paths = JsonMapper.builder().build().readTree(generatedJson()).path("paths");
        List<String> foreign = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                String name = operation.getKey().toUpperCase(java.util.Locale.ROOT) + " " + path.getKey();
                for (Map.Entry<String, JsonNode> response : operation.getValue().path("responses").properties()) {
                    Matcher code = ERROR_CODE.matcher(response.getValue().path("description").asString());
                    while (code.find()) {
                        if (!own.contains(code.group(1))) {
                            foreign.add(name + " " + response.getKey() + " — `" + code.group(1) + "`");
                        }
                    }
                }
            }
        }
        assertThat(foreign).isEmpty();
    }

    /** ops-api 와 공통 라이브러리의 {@code ErrorCode} 구현(enum)의 코드 전부. */
    private static Set<String> ownErrorCodes() throws ClassNotFoundException {
        var scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AssignableTypeFilter(
                com.dawnline.common.error.ErrorCode.class));
        Set<String> codes = new java.util.TreeSet<>();
        for (String base : List.of("com.dawnline.ops", "com.dawnline.common")) {
            for (var candidate : scanner.findCandidateComponents(base)) {
                Class<?> type = Class.forName(candidate.getBeanClassName());
                Object[] constants = type.getEnumConstants();
                if (constants != null) {
                    for (Object constant : constants) {
                        codes.add(((com.dawnline.common.error.ErrorCode) constant).code());
                    }
                }
            }
        }
        return codes;
    }

    /**
     * 가리킨 코어 오퍼레이션의 응답 코드들 — {@code {service}} 면 코어 넷 각각.
     *
     * @return 풀리지 않으면 {@code null}
     */
    private static @org.jspecify.annotations.Nullable List<Set<String>> coreResponses(String file, String operationId)
            throws Exception {
        List<String> files = file.contains("{service}")
                ? CORES.stream().map(core -> file.replace("{service}", core)).toList() : List.of(file);
        List<Set<String>> found = new ArrayList<>();
        for (String each : files) {
            Set<String> codes = null;
            for (Map<String, Object> operation : coreOperations(each).values()) {
                if (operationId.equals(operation.get("operationId"))) {
                    codes = responseCodes(operation);
                }
            }
            if (codes == null) {
                return null;
            }
            found.add(codes);
        }
        return found;
    }

    /** 가리킴이 없는 위임 오퍼레이션 — 같은 메서드 · 경로의 코어 오퍼레이션(§5.5 「경로는 코어의 것을 그대로」). */
    private static @org.jspecify.annotations.Nullable List<Set<String>> samePathInCores(String name) throws Exception {
        List<Set<String>> found = new ArrayList<>();
        for (String core : CORES) {
            Map<String, Object> operation = coreOperations(core + "-service.yaml").get(name);
            if (operation != null) {
                found.add(responseCodes(operation));
            }
        }
        return found.isEmpty() ? null : found;
    }

    /** 코어 문서의 오퍼레이션 — 「메서드 경로」 → 오퍼레이션. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> coreOperations(String file) throws Exception {
        Path contract = CONTRACT.resolveSibling(file);
        if (!Files.exists(contract)) {
            return Map.of();
        }
        Map<String, Object> document = new org.yaml.snakeyaml.Yaml().load(Files.readString(contract, StandardCharsets.UTF_8));
        Map<String, Map<String, Object>> operations = new java.util.LinkedHashMap<>();
        ((Map<String, Map<String, Object>>) document.get("paths")).forEach((path, methods) -> methods.forEach(
                (method, operation) -> operations.put(method.toUpperCase(java.util.Locale.ROOT) + " " + path,
                        (Map<String, Object>) operation)));
        return operations;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> responseCodes(Map<String, Object> operation) {
        // 키는 YAML 에서 따옴표 문자열이지만, 따옴표가 빠지면 정수로 읽힌다 — 문자열로 맞춘다.
        Set<String> codes = new java.util.TreeSet<>();
        ((Map<Object, Object>) operation.get("responses")).keySet().forEach(code -> codes.add(String.valueOf(code)));
        return codes;
    }

    /** {@code type} 이 문자열이든 배열이든 그 값들. */
    private static List<String> typesOf(JsonNode property) {
        JsonNode type = property.path("type");
        return type.isArray() ? texts(type) : List.of(type.asString());
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    @Test
    void 커밋된_문서가_코드와_같다() throws Exception {
        String generated = generatedYaml();

        if (Boolean.getBoolean(UPDATE_FLAG)) {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, generated, StandardCharsets.UTF_8);
            return;
        }

        assertThat(Files.exists(CONTRACT))
                .as("%s 가 없습니다. ./gradlew :services:ops-api:updateOpenApi 로 만드세요.", CONTRACT)
                .isTrue();
        assertThat(Files.readString(CONTRACT, StandardCharsets.UTF_8))
                .as("OpenAPI 문서가 코드와 어긋납니다. ./gradlew :services:ops-api:updateOpenApi 로 다시 만드세요.")
                .isEqualTo(generated);
    }

    private String generatedJson() throws Exception {
        return mockMvc.perform(get("/v3/api-docs").header("Authorization", VIEWER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String generatedYaml() throws Exception {
        return mockMvc.perform(get("/v3/api-docs.yaml").header("Authorization", VIEWER))
                .andExpect(status().isOk())
                // springdoc 의 YAML 응답에는 charset 이 없어 MockMvc 가 ISO-8859-1 로 읽는다.
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
