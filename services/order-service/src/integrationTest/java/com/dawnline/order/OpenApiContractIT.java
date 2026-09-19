package com.dawnline.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dawnline.common.openapi.OpenApiResponses;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OpenAPI 문서와 코드의 일치 (DESIGN.md §5.1, §14).
 *
 * <p>{@code contracts/openapi/order-service.yaml} 은 <strong>생성물</strong>이다. 손으로 고치면
 * 이 테스트가 깨진다 — API 문서가 코드보다 늦게 따라오는 흔한 상태를 막는 유일한 방법이다.
 *
 * <p><strong>다시 만들려면</strong>: {@code ./gradlew :services:order-service:updateOpenApi}
 * (또는 {@code integrationTest -Ddawnline.openapi.update=true}).
 *
 * <p>이 테스트가 계약 테스트({@code contracts/events})와 다른 점: 이벤트 계약은 사람이 먼저 쓰고
 * 코드가 따라가지만(불변규칙 8), OpenAPI 는 코드가 진실이고 문서가 따라간다. REST 표면은
 * 컨트롤러 시그니처에 이미 다 적혀 있어서, 그것을 두 곳에 적으면 어긋날 자리만 생긴다.
 */
@SpringBootTest(classes = OrderApplication.class)
@AutoConfigureMockMvc
@DisplayName("OpenApiContractIT — 문서가 코드와 어긋나지 않는다")
class OpenApiContractIT extends OrderIntegrationTestBase {

    /** 저장소에 커밋되는 문서. */
    private static final Path CONTRACT = Path.of("../../contracts/openapi/order-service.yaml");

    /** 이 값을 주면 문서를 다시 쓴다. */
    private static final String UPDATE_FLAG = "dawnline.openapi.update";

    /** 오류 본문의 스키마 이름 (RFC 9457, {@code org.springframework.http.ProblemDetail}). */
    private static final String PROBLEM_DETAIL = "ProblemDetail";

    @Autowired
    private MockMvc mockMvc;

    /** 이 테스트는 Redis·릴레이와 무관하다. 죽은 주소로 두어 컨텍스트를 가볍게 만든다. */
    @DynamicPropertySource
    static void noExternals(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> "1");
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    /** 검사 편의를 위한 비검사 버전. */
    private String catchYaml() {
        try {
            return generatedYaml();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 같은 문서의 JSON 표현. 구조로 읽을 때는 파서를 더 들이지 않으려고 이쪽을 쓴다. */
    private String generatedJson() throws Exception {
        return mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String generatedYaml() throws Exception {
        return mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                // springdoc 의 YAML 응답에는 charset 이 없어 MockMvc 가 ISO-8859-1 로 읽는다.
                // 그대로 두면 한글 설명이 깨진 채로 파일에 저장된다.
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void springdoc_이_Boot_4_에서_문서를_만든다() throws Exception {
        // CLAUDE.md: Boot 4 호환이 불확실한 라이브러리는 빌드로 확인한다. 이 어설션이 그 확인이다.
        String yaml = generatedYaml();

        assertThat(yaml).contains("openapi: 3.");
        assertThat(yaml).contains("Dawnline order-service");
    }

    @Test
    void 설계서_5_1_의_네_엔드포인트가_모두_문서에_있다() throws Exception {
        String yaml = generatedYaml();

        assertThat(yaml).contains("/api/v1/orders:");
        assertThat(yaml).contains("/api/v1/orders/{orderId}:");
        assertThat(yaml).contains("/api/v1/orders/{orderId}/cancel:");
    }

    @Test
    void 문서의_주소가_정식_형태다() {
        // 매핑은 /api/{version}/orders 이고 springdoc 은 그 자리를 해석된 버전 값으로 채워
        // /api/1/orders 를 적는다. 그 주소로도 요청은 통하지만 설계서(§5.1)와 Location 헤더가
        // 쓰는 정식 주소가 아니다 — 문서를 보고 만든 클라이언트가 지원한다고 말한 적 없는 형태를 쓰게 된다.
        assertThat(catchYaml()).doesNotContain("/api/1/orders").doesNotContain("{version}");
    }

    @Test
    void 멱등_키_헤더가_필수로_문서화된다() throws Exception {
        // 이것을 모르면 첫 요청부터 400 이다. 문서에 없으면 API 가 없는 것과 같다.
        String yaml = generatedYaml();

        assertThat(yaml).contains("Idempotency-Key");
    }

    @Test
    void 오류_응답이_문서에_있다() throws Exception {
        // springdoc 은 반환 타입만 본다. 예외로 나가는 상태 코드는 적어 주지 않으면 문서에 없고,
        // 그러면 클라이언트는 429·422·409 가 존재한다는 것조차 모른다.
        String yaml = generatedYaml();

        assertThat(yaml).contains("\"429\"");
        assertThat(yaml).contains("\"422\"");
        assertThat(yaml).contains("\"409\"");
        assertThat(yaml).contains("\"404\"");
    }

    @Test
    void 오류_응답의_본문은_모두_Problem_Details_다() throws Exception {
        // 코드를 열거하지 않는다 — **2xx 가 아닌 전부**다. 열거하면 새로 생긴 코드가 조용히 검사
        // 밖에 남는다(CLAUDE.md 「집합을 도는 검사는 열거하지 않고 전체에서 뺀다」).
        OpenApiResponses responses = OpenApiResponses.parse(generatedJson());

        assertThat(responses.errorBodies())
                .as("전제 — 문서에 2xx 아닌 응답이 있다. 없으면 아래 어설션은 아무것도 보지 않는다")
                .isNotEmpty();
        assertThat(responses.errorBodiesNotUsing(PROBLEM_DETAIL))
                .as("오류 본문은 RFC 9457 Problem Details 다 (CLAUDE.md 예외 규칙). springdoc 은 "
                        + "@ApiResponse 에 content 를 주지 않으면 **메서드 반환 타입**을 모든 응답에 "
                        + "붙이므로, 문서가 「404 의 본문은 OrderView」라고 말하게 된다 — 그 문서를 "
                        + "보고 만든 클라이언트는 오류를 파싱하지 못한다")
                .isEmpty();
    }

    @Test
    void 성공_응답에는_오류_본문이_실리지_않는다() throws Exception {
        // 위 검사가 2xx 를 **왜** 제외하는지를 말하는 검사다 — 성공 본문은 유스케이스의 반환
        // 타입이어야 한다. 이것이 없으면 다음 사람은 제외가 「검토했는데 제외」인지 「잊었는지」를
        // 구별할 수 없다 (DESIGN.md §13 규칙 2).
        assertThat(OpenApiResponses.parse(generatedJson()).successBodiesUsing(PROBLEM_DETAIL))
                .as("성공 응답이 Problem Details 를 싣고 있다 — 오류를 200 으로 내보내고 있다는 뜻이다")
                .isEmpty();
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
                .as("%s 가 없습니다. -D%s=true 로 다시 만드세요.", CONTRACT, UPDATE_FLAG)
                .isTrue();
        assertThat(Files.readString(CONTRACT, StandardCharsets.UTF_8))
                .as("OpenAPI 문서가 코드와 어긋납니다. "
                        + "./gradlew :services:order-service:updateOpenApi 로 다시 만드세요.")
                .isEqualTo(generated);
    }
}
