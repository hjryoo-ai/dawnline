package com.dawnline.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dawnline.common.openapi.OpenApiResponses;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
class OpenApiContractIT extends OpsIntegrationTestBase {

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
