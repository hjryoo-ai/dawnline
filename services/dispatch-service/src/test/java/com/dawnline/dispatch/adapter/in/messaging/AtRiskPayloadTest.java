package com.dawnline.dispatch.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.ValidationException;
import com.dawnline.dispatch.application.port.in.ReplanRouteUseCase.Outcome;
import com.dawnline.dispatch.application.port.in.ReplanRouteUseCase.ReplanCommand;
import com.dawnline.messaging.Topics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code delivery.at-risk.v1} 중 dispatch 가 읽는 칸 — <strong>계약의 예시 파일</strong>로 본다.
 *
 * <p>손으로 쓴 JSON 으로 시험하면 이쪽이 상상한 모양만 통과하고, 계약이 바뀌어도 초록이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("AtRiskPayload — 네 칸만 읽는다")
class AtRiskPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CONTRACTS = locateRepoRoot().resolve("contracts/events/examples");
    private static final String EXAMPLE = "delivery.at-risk.v1.example.json";

    @Test
    void 토픽_이름이_규칙과_같다() {
        // @KafkaListener 의 topics 는 컴파일 타임 상수여야 해서 리터럴로 적는다. 오타는
        // 컨슈머가 조용히 아무것도 받지 않는 형태로 나타난다 — 여기서는 「재계획이 돌지 않는다」다.
        assertThat(AtRiskListener.AT_RISK_TOPIC)
                .isEqualTo(Topics.forEvent("delivery.at-risk", 1));
    }

    @Test
    void 소비자_이름이_다른_리스너들과_같다() {
        // processed_events.consumer 는 서비스 단위다 (§8.5).
        assertThat(AtRiskListener.CONSUMER).isEqualTo(DeliveryStatusListener.CONSUMER);
    }

    @Test
    void 예시가_명령이_된다() {
        ReplanCommand command = AtRiskPayload.toCommand(payload());

        assertThat(command.routeId())
                .isEqualTo(UUID.fromString("01a04e09-854a-770c-b7b7-03325dccc708"));
        assertThat(command.campId())
                .isEqualTo(UUID.fromString("01a04d71-2f3c-7a10-9e55-8c2b41d9f004"));
        assertThat(command.detectedAt().toString()).isEqualTo("2026-08-29T16:12:40.118Z");
        assertThat(command.deviation()).isEqualTo(Duration.ofSeconds(1380));
    }

    @Test
    void remainingStops_가_없어도_명령이_된다() {
        // 읽지 않는 것이 이 클래스의 «결정» 이지 게으름이 아니다 (ADR-048 결정 1). 그 목록은
        // tracking 이 본 것이고 dispatch 는 같은 것을 자기 route_stops 에 갖고 있다 — 둘을 다
        // 받아 두면 언젠가 그쪽을 쓰게 되고, 그 순간 「진실 하나」가 갈린다.
        JsonNode payload = payload();
        ((tools.jackson.databind.node.ObjectNode) payload).remove("remainingStops");

        assertThat(AtRiskPayload.toCommand(payload).routeId()).isNotNull();
    }

    @Test
    void 필수_칸이_없으면_소리를_낸다() {
        // delivery.status 와 달리 빈 값을 돌려주는 자리가 없다 — 네 칸이 전부 스칼라라
        // 「아직 모르는 값」이 있을 수 없고, 없으면 그것은 계약 위반이다.
        JsonNode payload = payload();
        ((tools.jackson.databind.node.ObjectNode) payload).remove("deviationSeconds");

        assertThatThrownBy(() -> AtRiskPayload.toCommand(payload))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("deviationSeconds");
    }

    @Test
    void 결과_라벨은_설계서의_다섯_갈래와_같다() {
        // §9.1 이 적은 문자열 그대로여야 대시보드의 쿼리가 산다. 열거하지 않고 전체에서
        // 만들어 비교한다 — 값이 늘면 이 검사가 따라온다.
        assertThat(Arrays.stream(Outcome.values()).map(Outcome::label).toList())
                .containsExactly("applied", "cooldown", "no-anchor", "no-candidate", "no-gain");
    }

    private static JsonNode payload() {
        try {
            return MAPPER.readTree(Files.readString(CONTRACTS.resolve(EXAMPLE))).get("payload");
        } catch (IOException e) {
            throw new UncheckedIOException("계약 예시를 읽지 못했습니다: " + EXAMPLE, e);
        }
    }

    private static Path locateRepoRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("contracts").resolve("events"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("contracts/events 를 찾지 못했습니다. 작업 디렉터리=" + current);
    }
}
