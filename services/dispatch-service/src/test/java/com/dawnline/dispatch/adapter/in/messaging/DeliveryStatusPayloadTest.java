package com.dawnline.dispatch.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.ValidationException;
import com.dawnline.dispatch.application.port.in.RecordDeliveryStatusUseCase.DeliveryStatusCommand;
import com.dawnline.dispatch.domain.RouteStopStatus;
import com.dawnline.messaging.Topics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code delivery.status.v1} 중 dispatch 가 읽는 칸 — <strong>계약의 예시 파일</strong>로 본다.
 *
 * <p>손으로 쓴 JSON 으로 시험하면 이쪽이 상상한 모양만 통과하고, 계약이 바뀌어도 초록이다.
 * <strong>소비자 주도 계약</strong>이므로 읽는 칸은 다섯뿐이고 나머지는 없는 것처럼 지나간다 —
 * {@code failureReason} 이 있는 예시가 그대로 통과하는 것이 그 증거다(§4.7).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("DeliveryStatusPayload — 계약 예시에서 명령까지")
class DeliveryStatusPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CONTRACTS = locateRepoRoot().resolve("contracts/events/examples");

    @Test
    void 토픽_이름이_규칙과_같다() {
        // @KafkaListener 의 topics 는 컴파일 타임 상수여야 해서 리터럴로 적는다. 오타는
        // 컨슈머가 조용히 아무것도 받지 않는 형태로 나타난다.
        assertThat(DeliveryStatusListener.DELIVERY_STATUS_TOPIC)
                .isEqualTo(Topics.forEvent("delivery.status", 1));
    }

    @Test
    void 소비자_이름이_다른_리스너들과_같다() {
        // processed_events.consumer 값이다 (§8.5). 리스너마다 다르면 같은 이벤트를 두 번 처리한다.
        assertThat(DeliveryStatusListener.CONSUMER).isEqualTo("dispatch-service");
    }

    @Test
    void 메트릭_태그의_이벤트_타입이_계약의_이벤트_타입이다() {
        assertThat(Topics.forEvent(
                com.dawnline.dispatch.application.DispatchMetrics.DELIVERY_STATUS_EVENT_TYPE, 1))
                .isEqualTo(DeliveryStatusListener.DELIVERY_STATUS_TOPIC);
    }

    @Test
    void 완료_예시가_명령이_된다() {
        DeliveryStatusCommand command = command("delivery.status.v1.example.json");

        assertThat(command.routeId())
                .isEqualTo(UUID.fromString("01a04e09-854a-770c-b7b7-03325dccc708"));
        assertThat(command.stopSeq()).isEqualTo(1);
        assertThat(command.orderIds())
                .containsExactly(UUID.fromString("01a04dad-80da-7f6e-a63a-e91c103516b0"));
        assertThat(command.status()).isEqualTo(RouteStopStatus.COMPLETED);
        assertThat(command.occurredAt().toString()).isEqualTo("2026-08-29T15:44:03.501Z");
    }

    @Test
    void 실패_예시는_사유를_읽지_않고도_명령이_된다() {
        // failureReason 은 dispatch 의 질문에 답하지 않는다. 그리고 그 값에는 개인정보가 섞일 수
        // 있어(§9.3) 계획 테이블로 가져올 이유가 더더욱 없다.
        DeliveryStatusCommand command = command("delivery.status.v1.failed.example.json");

        assertThat(command.status()).isEqualTo(RouteStopStatus.FAILED);
        assertThat(command.orderIds()).hasSize(2);
        assertThat(command.stopSeq()).isEqualTo(2);
    }

    @Test
    void 모르는_상태값은_빈_값이지_예외가_아니다() {
        // §4.7 이 같은 major 안에서 enum 값 추가를 허용한다. 예외를 던지면 그 이벤트가 DLQ 로
        // 가고, 그 DLQ 는 사람이 봐도 할 일이 없다.
        JsonNode payload = withStatus("RETURNED");

        assertThat(DeliveryStatusPayload.toCommand(payload)).isEmpty();
    }

    @Test
    void 이_이벤트가_나를_수_없는_상태도_빈_값이다() {
        // PLANNED·CANCELLED 는 route_stops 의 값이지 스캔이 말할 수 있는 값이 아니다.
        assertThat(DeliveryStatusPayload.toCommand(withStatus("PLANNED"))).isEmpty();
        assertThat(DeliveryStatusPayload.toCommand(withStatus("CANCELLED"))).isEmpty();
    }

    @Test
    void 필수_칸이_없으면_소리를_낸다() {
        JsonNode payload = payloadOf("delivery.status.v1.example.json");
        ((tools.jackson.databind.node.ObjectNode) payload).remove("orderIds");

        assertThatThrownBy(() -> DeliveryStatusPayload.toCommand(payload))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("orderIds");
    }

    // --- 읽기 ----------------------------------------------------------------

    private static JsonNode withStatus(String status) {
        JsonNode payload = payloadOf("delivery.status.v1.example.json");
        ((tools.jackson.databind.node.ObjectNode) payload).put("status", status);
        return payload;
    }

    private static DeliveryStatusCommand command(String fileName) {
        Optional<DeliveryStatusCommand> command =
                DeliveryStatusPayload.toCommand(payloadOf(fileName));
        assertThat(command).as("계약 예시 %s 가 명령이 되지 않았다", fileName).isPresent();
        return command.orElseThrow();
    }

    private static JsonNode payloadOf(String fileName) {
        try {
            return MAPPER.readTree(Files.readString(CONTRACTS.resolve(fileName))).get("payload");
        } catch (IOException e) {
            throw new UncheckedIOException("계약 예시를 읽지 못했습니다: " + fileName, e);
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
