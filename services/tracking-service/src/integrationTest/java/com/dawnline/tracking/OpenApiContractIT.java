package com.dawnline.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * OpenAPI 문서와 코드의 일치 (DESIGN.md §5.4, §14).
 *
 * <p>{@code contracts/openapi/tracking-service.yaml} 은 <strong>생성물</strong>이다. 손으로 고치면
 * 이 테스트가 깨진다 — API 문서가 코드보다 늦게 따라오는 흔한 상태를 막는 유일한 방법이다.
 *
 * <p>이 문서를 읽는 쪽이 사람만은 아니다. Phase 5-2 의 {@code sim-runner} 기사 시뮬레이터가
 * <strong>다른 모듈에서</strong> 이 엔드포인트를 부르고(§5.6), 두 모듈이 공유하는 것은 이
 * 문서뿐이다 — 어긋나면 시뮬레이터의 런타임 오류가 된다.
 *
 * <p><strong>다시 만들려면</strong>: {@code ./gradlew :services:tracking-service:updateOpenApi}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("OpenAPI 문서가 코드와 어긋나지 않는다")
class OpenApiContractIT extends TrackingIntegrationTestBase {

    /** 저장소에 커밋되는 문서. */
    private static final Path CONTRACT = Path.of("../../contracts/openapi/tracking-service.yaml");

    /** 이 값을 주면 문서를 다시 쓴다. */
    private static final String UPDATE_FLAG = "dawnline.openapi.update";

    @Autowired
    private MockMvc mockMvc;

    /**
     * 이 IT 는 발행도 스케줄도 브로커도 보지 않는다 — 문서만 읽는다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void 공유_자원을_끈다(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("dawnline.tracking.partitions.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Test
    void springdoc_이_Boot_4_에서_문서를_만든다() throws Exception {
        // CLAUDE.md: Boot 4 호환이 불확실한 라이브러리는 빌드로 확인한다. 이 어설션이 그 확인이다.
        String yaml = generatedYaml();

        assertThat(yaml).contains("openapi: 3.");
        assertThat(yaml).contains("Dawnline tracking-service");
    }

    @Test
    void 설계서_5_4_의_스캔_엔드포인트가_문서에_있다() throws Exception {
        assertThat(generatedYaml())
                .contains("/api/v1/routes/{routeId}/stops/{stopSeq}/events:");
    }

    @Test
    void 문서의_주소가_정식_형태다() {
        // 매핑은 /api/{version}/... 이고 springdoc 은 그 자리를 해석된 버전 값으로 채워
        // /api/1/... 을 적는다. 그 주소로도 요청은 통하지만 설계서(§5.4)가 쓰는 정식 주소가 아니다.
        assertThat(catchYaml()).doesNotContain("/api/1/routes").doesNotContain("{version}");
    }

    @Test
    void 오류_응답이_문서에_있다() throws Exception {
        // springdoc 은 반환 타입만 본다. 예외로 나가는 상태 코드는 적어 주지 않으면 문서에 없고,
        // 그러면 단말을 만드는 사람은 404·409 가 존재한다는 것조차 모른다.
        String yaml = generatedYaml();

        assertThat(yaml).contains("\"400\"").contains("\"404\"").contains("\"409\"");
        assertThat(yaml)
                .as("오류 본문은 Problem Details 다. 적지 않으면 springdoc 이 메서드 반환 타입을 "
                        + "모든 응답에 붙여, 문서가 「404 의 본문은 ScanResult 다」라고 말한다")
                .contains("ProblemDetail");
    }

    @Test
    void 재시도가_안전하다는_사실이_문서에_있다() throws Exception {
        // 멱등 키 헤더가 없는 API 다. 「재시도해도 되는가」는 단말이 가장 먼저 묻는 질문이고,
        // 문서가 답하지 않으면 각자 다르게 가정한다 (§8.5).
        assertThat(generatedYaml()).contains("재시도해도 안전하다");
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
                        + "./gradlew :services:tracking-service:updateOpenApi 로 다시 만드세요.")
                .isEqualTo(generated);
    }

    /** 검사 편의를 위한 비검사 버전. */
    private String catchYaml() {
        try {
            return generatedYaml();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String generatedYaml() throws Exception {
        return mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                // springdoc 의 YAML 응답에는 charset 이 없어 MockMvc 가 ISO-8859-1 로 읽는다.
                // 그대로 두면 한글 설명이 깨진 채로 파일에 저장된다.
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
