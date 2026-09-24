package com.dawnline.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 「코어의 모든 쓰기는 토큰 대상이고, 면제는 명시 목록뿐이다」의 강제 수단 (DESIGN.md §10 · §13, ADR-055 결정 3).
 *
 * <p>코어의 {@code OpenApiContractIT} 가 구현한다. 검사는 <strong>생성된 OpenAPI 문서에서</strong> 뽑는다 — {@code GET}
 * 이 아닌 오퍼레이션을 전부 읽어 토큰 없이, 틀린 토큰으로 부른다. 인터셉터의 등록 여부를 보지 않고 결과를 보므로,
 * 어느 서비스에서 등록이 빠져도(자동 설정 제외, 설정 실수) 그 서비스의 쓰기가 조용히 열리지 않는다.
 *
 * <p>열거하는 쪽은 면제다(CLAUDE.md 「집합을 도는 검사는 열거하지 않고 전체에서 뺀다」). 그리고 면제는 읽힌다 —
 * {@link UnauthenticatedWrite} 가 붙은 핸들러 집합이 {@link #unauthenticatedWrites()} 와 같아야 한다.
 *
 * <p>토큰 없는 요청은 핸들러에 닿지 않으므로 이 검사는 아무것도 바꾸지 않는다. 면제 오퍼레이션은 핸들러에 닿지만
 * 빈 본문과 없는 id 라 검증(400)이나 조회(404)에서 멈춘다.
 */
public interface InternalTokenSurfaceContract {

    /** 경로 변수의 자리 값 — UUID 칸. 어느 행도 가리키지 않는다. */
    String SAMPLE_UUID = "00000000-0000-7000-8000-000000000000";

    /** 틀린 토큰 — 길이는 충분하다(길이로 걸러지는 것이 아니라 값으로 걸러진다는 것을 본다). */
    String WRONG_TOKEN = "wrong-internal-token-for-contract-test-0123456789";

    /** @return 서비스의 MockMvc (실제 {@code DispatcherServlet}) */
    MockMvc mockMvc();

    /** @return 서비스의 컨텍스트 — 핸들러 매핑을 읽는다 */
    ApplicationContext applicationContext();

    /**
     * 이 서비스에서 토큰 없이 받는 쓰기 — {@code "POST /api/v1/orders"} 형태. §10 의 고객·현장 표면이다.
     *
     * @return 면제 목록. 운영자 표면만 있는 서비스는 비어 있다
     */
    Set<String> unauthenticatedWrites();

    @Test
    default void 문서의_쓰기는_면제_목록_밖이면_토큰_없이_401_이다() throws Exception {
        List<Operation> guarded = guardedWrites();
        assertThat(writeOperations())
                .as("전제 — 문서에 쓰기 오퍼레이션이 있다. 없으면 아래 어설션은 아무것도 보지 않는다")
                .isNotEmpty();

        assertThat(notRejected(guarded, null))
                .as("토큰 없이 401 internal-token-required 가 아닌 쓰기 — 운영자 쓰기가 감사 없이 열려 있다(ADR-055)")
                .isEmpty();
    }

    @Test
    default void 틀린_토큰도_401_이다() throws Exception {
        assertThat(notRejected(guardedWrites(), WRONG_TOKEN))
                .as("틀린 토큰으로 401 internal-token-required 가 아닌 쓰기")
                .isEmpty();
    }

    @Test
    default void 면제_목록의_쓰기는_문서에_있고_토큰_없이도_401_이_아니다() throws Exception {
        // 면제가 살아 있다 — 목록에 적힌 것이 문서에 없거나 막혀 있으면 목록이 거짓을 말한다.
        Map<String, Operation> byKey = new java.util.HashMap<>();
        writeOperations().forEach(operation -> byKey.put(operation.key(), operation));

        for (String exempt : unauthenticatedWrites()) {
            assertThat(byKey).as("면제 목록의 %s 가 문서에 없다", exempt).containsKey(exempt);
            MockHttpServletResponse response = perform(byKey.get(exempt), null);
            assertThat(response.getStatus()).as("면제된 %s 가 토큰 없이 401 이다", exempt).isNotEqualTo(401);
        }
    }

    @Test
    default void 면제_표시가_붙은_핸들러는_면제_목록과_같다() {
        // 쓰인 제외는 읽힌다 — 새 핸들러에 @UnauthenticatedWrite 를 붙이면 이 목록에도 적어야 초록이 된다.
        RequestMappingHandlerMapping mapping = applicationContext()
                .getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        Set<String> annotated = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
            if (!entry.getValue().hasMethodAnnotation(UnauthenticatedWrite.class)) {
                continue;
            }
            for (var method : entry.getKey().getMethodsCondition().getMethods()) {
                for (String pattern : entry.getKey().getPatternValues()) {
                    annotated.add(method.name() + " " + pattern.replace("{version}", "v1"));
                }
            }
        }
        assertThat(annotated).as("@UnauthenticatedWrite 가 붙은 핸들러 = 면제 목록")
                .isEqualTo(new TreeSet<>(unauthenticatedWrites()));
    }

    @Test
    default void 문서가_토큰을_요구한다고_말하는_쓰기는_면제_목록_밖의_쓰기와_같다() throws Exception {
        Set<String> documented = new TreeSet<>();
        Set<String> guarded = new TreeSet<>();
        for (Operation operation : writeOperations()) {
            if (operation.documentsToken()) {
                documented.add(operation.key());
            }
            if (!unauthenticatedWrites().contains(operation.key())) {
                guarded.add(operation.key());
            }
        }
        assertThat(documented).as("문서의 security: [%s] 가 붙은 쓰기 = 401 을 받는 쓰기", InternalToken.SECURITY_SCHEME)
                .isEqualTo(guarded);
    }

    // --- 문서에서 뽑기 ----------------------------------------------------------------------------

    /**
     * 문서의 오퍼레이션 하나.
     *
     * @param method         HTTP 메서드(대문자)
     * @param path           경로 템플릿
     * @param samplePath     경로 변수를 자리 값으로 채운 주소
     * @param documentsToken 문서가 이 오퍼레이션에 {@value InternalToken#SECURITY_SCHEME} 를 요구하는가
     */
    record Operation(String method, String path, String samplePath, boolean documentsToken) {

        /** @return {@code "POST /api/v1/orders"} */
        public String key() {
            return method + " " + path;
        }
    }

    private List<Operation> guardedWrites() throws Exception {
        return writeOperations().stream().filter(operation -> !unauthenticatedWrites().contains(operation.key()))
                .toList();
    }

    /** 문서의 {@code GET} 이 아닌 오퍼레이션 전부. {@code HEAD}·{@code OPTIONS}·{@code TRACE} 도 쓰기가 아니다. */
    private List<Operation> writeOperations() throws Exception {
        String json = mockMvc().perform(get("/v3/api-docs")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode paths = JsonMapper.builder().build().readTree(json).path("paths");
        List<Operation> operations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            for (Map.Entry<String, JsonNode> entry : path.getValue().properties()) {
                String method = entry.getKey().toUpperCase(java.util.Locale.ROOT);
                if (!InternalTokenInterceptor.WRITE_METHODS.contains(method)) {
                    continue;
                }
                JsonNode operation = entry.getValue();
                operations.add(new Operation(method, path.getKey(), samplePath(path.getKey(), operation),
                        documentsToken(operation)));
            }
        }
        return operations;
    }

    private static String samplePath(String template, JsonNode operation) {
        String sample = template;
        for (JsonNode parameter : operation.path("parameters")) {
            if (!"path".equals(parameter.path("in").asString())) {
                continue;
            }
            JsonNode schema = parameter.path("schema");
            String value = "uuid".equals(schema.path("format").asString()) ? SAMPLE_UUID
                    : "integer".equals(schema.path("type").asString()) ? "1" : "sample";
            sample = sample.replace("{" + parameter.path("name").asString() + "}", value);
        }
        return sample;
    }

    private static boolean documentsToken(JsonNode operation) {
        for (JsonNode requirement : operation.path("security")) {
            if (requirement.has(InternalToken.SECURITY_SCHEME)) {
                return true;
            }
        }
        return false;
    }

    // --- 부르기 --------------------------------------------------------------------------------

    /** 401 {@code internal-token-required} 를 받지 않은 오퍼레이션과 받은 것. 비면 전부 거부됐다. */
    private List<String> notRejected(List<Operation> operations, String token) throws Exception {
        List<String> open = new ArrayList<>();
        for (Operation operation : operations) {
            MockHttpServletResponse response = perform(operation, token);
            String body = response.getContentAsString(StandardCharsets.UTF_8);
            if (response.getStatus() != 401 || !body.contains("\"code\":\"internal-token-required\"")) {
                open.add(operation.key() + " → " + response.getStatus());
            }
        }
        return open;
    }

    private MockHttpServletResponse perform(Operation operation, String token) throws Exception {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(operation.method()), operation.samplePath())
                .contentType(MediaType.APPLICATION_JSON).content("{}");
        if (token != null) {
            builder.header(InternalToken.HEADER, token);
        }
        return mockMvc().perform(builder).andReturn().getResponse();
    }
}
