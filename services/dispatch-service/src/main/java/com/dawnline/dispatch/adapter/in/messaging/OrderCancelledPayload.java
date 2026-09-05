package com.dawnline.dispatch.adapter.in.messaging;

import com.dawnline.common.error.ValidationException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * {@code order.cancelled.v1} 페이로드 중 dispatch 가 쓰는 것 (DESIGN.md §6.10).
 *
 * <p>둘뿐이다 — 어느 주문인가, 언제 취소됐는가.
 *
 * <h2>{@code previousStatus} 를 보지 않는 이유</h2>
 * 계약은 소비자를 위해 {@code PLACED | PLANNED} 를 실어 준다. fulfillment 에게는 그 구분이
 * 일이 있고 없고를 가르지만(§5.2), dispatch 의 분기는 <strong>우리 쪽 stop 의 상태</strong>로
 * 자른다(ADR-026 결정 2). 발행자가 말하는 주문 상태로 자르면 두 서비스의 상태 머신이 한 판단을
 * 나눠 갖게 되고, 그 둘은 서로 다른 시각의 사실이다.
 *
 * <h2>{@code cancelledAt} 은 사건의 시각이다</h2>
 * 우리가 처리한 시각이 아니다(§4.2 {@code occurredAt} 과 같은 값). 후보의 {@code updated_at} 에
 * 그것을 쓰면 "언제 취소됐나" 에 답할 수 있고, 처리 시각을 쓰면 컨슈머 랙이 답을 오염시킨다.
 */
final class OrderCancelledPayload {

    private OrderCancelledPayload() {
    }

    /**
     * @param payload {@code order.cancelled} 페이로드
     */
    static UUID orderId(JsonNode payload) {
        return UUID.fromString(text(payload, "orderId"));
    }

    /**
     * @param payload {@code order.cancelled} 페이로드
     */
    static Instant cancelledAt(JsonNode payload) {
        return Instant.parse(text(payload, "cancelledAt"));
    }

    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            throw new ValidationException("order.cancelled 에 %s 가 없습니다".formatted(field),
                    Map.of("field", field));
        }
        return node.asString();
    }
}
