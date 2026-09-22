package com.dawnline.dispatch.adapter.in.messaging;

import com.dawnline.common.error.ValidationException;
import com.dawnline.dispatch.application.port.in.RecordDeliveryStatusUseCase.DeliveryStatusCommand;
import com.dawnline.dispatch.domain.RouteStopStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * {@code delivery.status.v1} 페이로드 중 dispatch 가 쓰는 것 (DESIGN.md §4.1, ADR-047).
 *
 * <p><strong>소비자 주도 계약</strong>이다 — 여기 적힌 다섯 칸만 읽고 나머지는 없는 것처럼
 * 지나간다({@code failureReason} 은 dispatch 의 질문에 답하지 않는다). 계약이 같은 major 안에서
 * 필드를 더하면 이 클래스는 그대로다(§4.7).
 *
 * <h2>{@code stopSeq} 는 읽되 조회에 쓰지 않는다</h2>
 * 개정이 {@code seq} 의 뜻을 바꾸기 때문이다(ADR-047 결정 1). 그래도 읽는 이유는 저장된 순번과
 * 다른지를 로그로 남기기 위해서다 — 그 차이가 「개정이 언제 끼어들었나」를 잰다.
 */
final class DeliveryStatusPayload {

    private DeliveryStatusPayload() {
    }

    /**
     * 명령으로 바꾼다. 계약이 모르는 {@code status} 는 <strong>빈 값</strong>이다.
     *
     * <p>§4.7 이 같은 major 안에서 enum 값 추가를 허용하므로 모르는 값은 실패가 아니라
     * <em>아직 구현하지 않은 것</em>이다. 예외를 던지면 그 이벤트가 DLQ 로 가고, 그 DLQ 는
     * 사람이 봐도 할 일이 없다.
     *
     * @param payload {@code delivery.status} 페이로드
     * @return 명령. 상태를 모르면 빈 값
     */
    static Optional<DeliveryStatusCommand> toCommand(JsonNode payload) {
        Optional<RouteStopStatus> status = status(payload);
        if (status.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DeliveryStatusCommand(
                UUID.fromString(text(payload, "routeId")),
                integer(payload, "stopSeq"),
                orderIds(payload),
                status.get(),
                Instant.parse(text(payload, "occurredAt"))));
    }

    /** 계약의 {@code status} — {@code ARRIVED}·{@code COMPLETED}·{@code FAILED}. */
    private static Optional<RouteStopStatus> status(JsonNode payload) {
        String value = text(payload, "status");
        for (RouteStopStatus candidate : RouteStopStatus.values()) {
            // 열거하지 않고 전체에서 고른다 — 계약에 값이 늘면 여기 손대지 않아도 따라온다.
            // 방문한 상태가 아닌 것(PLANNED·CANCELLED)은 이 이벤트가 나를 수 없다.
            if (candidate.name().equals(value) && candidate.visited()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static List<UUID> orderIds(JsonNode payload) {
        JsonNode node = payload.get("orderIds");
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new ValidationException("delivery.status 에 orderIds 가 없습니다",
                    Map.of("field", "orderIds"));
        }
        List<UUID> ids = new ArrayList<>(node.size());
        node.forEach(element -> ids.add(UUID.fromString(element.asString())));
        return List.copyOf(ids);
    }

    private static int integer(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || !node.isNumber()) {
            throw new ValidationException("delivery.status 에 %s 가 없습니다".formatted(field),
                    Map.of("field", field));
        }
        return node.asInt();
    }

    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            throw new ValidationException("delivery.status 에 %s 가 없습니다".formatted(field),
                    Map.of("field", field));
        }
        return node.asString();
    }
}
