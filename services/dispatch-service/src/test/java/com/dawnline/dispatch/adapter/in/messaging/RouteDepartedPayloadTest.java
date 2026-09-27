package com.dawnline.dispatch.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.dispatch.application.DispatchMetrics;
import com.dawnline.dispatch.application.port.in.RecordRouteDepartureUseCase.RouteDeparted;
import com.dawnline.messaging.Topics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code delivery.route-departed.v1} 중 dispatch 가 읽는 칸 — <strong>계약의 예시 파일</strong>로 본다 (ADR-072).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("RouteDepartedPayload — 계약 예시에서 명령까지")
class RouteDepartedPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void 토픽_이름이_규칙과_같다() {
        // @KafkaListener 의 topics 는 컴파일 타임 상수여야 해서 리터럴로 적는다. 오타는 컨슈머가 조용히 아무것도 받지 않는 형태로 나타난다.
        assertThat(RouteDepartedListener.ROUTE_DEPARTED_TOPIC).isEqualTo(Topics.forEvent("delivery.route-departed", 1));
        assertThat(Topics.forEvent(DispatchMetrics.ROUTE_DEPARTED_EVENT_TYPE, 1))
                .as("메트릭 태그의 이벤트 타입이 계약의 이벤트 타입이다").isEqualTo(RouteDepartedListener.ROUTE_DEPARTED_TOPIC);
    }

    @Test
    void 소비자_이름이_다른_리스너들과_같다() {
        // processed_events.consumer 는 서비스 단위다 (§8.5).
        assertThat(RouteDepartedListener.CONSUMER).isEqualTo(DeliveryStatusListener.CONSUMER);
    }

    @Test
    void 계약_예시에서_라우트와_떠난_시각을_읽는다() {
        JsonNode payload = example().get("payload");

        RouteDeparted command = RouteDepartedPayload.toCommand(payload);

        assertThat(command.routeId()).isEqualTo(UUID.fromString(payload.get("routeId").asString()));
        assertThat(command.departedAt()).isEqualTo(Instant.parse(payload.get("departedAt").asString()));
    }

    private static JsonNode example() {
        Path file = locateRepoRoot().resolve("contracts/events/examples/delivery.route-departed.v1.example.json");
        try {
            return MAPPER.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
