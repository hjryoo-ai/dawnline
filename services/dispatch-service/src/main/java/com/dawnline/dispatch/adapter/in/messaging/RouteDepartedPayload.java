package com.dawnline.dispatch.adapter.in.messaging;

import com.dawnline.common.error.ValidationException;
import com.dawnline.dispatch.application.port.in.RecordRouteDepartureUseCase.RouteDeparted;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * {@code delivery.route-departed.v1} 페이로드 중 dispatch 가 쓰는 것 (ADR-072).
 *
 * <p><strong>두 칸만</strong> 읽는다 — {@code routeId} · {@code departedAt}. {@code plannedDeparture} 는 dispatch 가 자기 계획으로 갖고 있고
 * ({@code routes.planned_departure}), {@code revision} 으로는 거르지 않는다 — 출발은 사실이다(ADR-047 결정 3). 계약이 같은 major 안에서 칸을
 * 더해도 이 클래스는 그대로다(§4.7).
 */
final class RouteDepartedPayload {

    private RouteDepartedPayload() {
    }

    /**
     * @param payload {@code delivery.route-departed} 페이로드
     * @return 명령
     */
    static RouteDeparted toCommand(JsonNode payload) {
        return new RouteDeparted(UUID.fromString(text(payload, "routeId")), Instant.parse(text(payload, "departedAt")));
    }

    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            throw new ValidationException("delivery.route-departed 에 %s 가 없습니다".formatted(field),
                    Map.of("field", field));
        }
        return node.asString();
    }
}
