package com.dawnline.dispatch.adapter.in.messaging;

import com.dawnline.common.error.ValidationException;
import com.dawnline.dispatch.application.port.in.ReplanRouteUseCase.ReplanCommand;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * {@code delivery.at-risk.v1} 페이로드 중 dispatch 가 쓰는 것 (DESIGN.md §6.8, ADR-048).
 *
 * <h2>네 칸만 읽는다 — 그리고 {@code remainingStops} 는 그중에 없다</h2>
 * <strong>소비자 주도 계약</strong>이고, 여기서는 그 원칙이 설계 결정과 겹친다.
 * {@code remainingStops} 는 계약의 {@code required} 이지만 <em>tracking 이 본 것</em>이고,
 * dispatch 는 같은 것을 자기 {@code route_stops} 에 갖고 있다(ADR-047 이 채웠다). 둘을 다 받아
 * 두면 언젠가 그쪽을 쓰게 되고, 그 순간 「진실 하나」가 갈린다 — 읽지 않는 것이 이 클래스의
 * <em>결정</em>이지 게으름이 아니다(ADR-048 결정 1).
 *
 * <p>{@code deviationSeconds} 는 읽는다. 쓰지 않기 위해서가 아니라 <strong>견주기</strong>
 * 위해서다 — dispatch 가 자기 {@code actual_at} 으로 계산한 값과 갈리면
 * {@code dawnline_at_risk_deviation_mismatch_total} 이 오른다(결정 2).
 */
final class AtRiskPayload {

    private AtRiskPayload() {
    }

    /**
     * 명령으로 바꾼다.
     *
     * <p>{@code delivery.status} 와 달리 <strong>빈 값을 돌려주는 경우가 없다</strong>. 저쪽은
     * enum 값이 늘 수 있어 「아직 모르는 값」이 있었지만, 이 계약의 네 칸은 전부 스칼라라
     * 모를 것이 없다. 없으면 그것은 계약 위반이고 예외다.
     *
     * @param payload {@code delivery.at-risk} 페이로드
     */
    static ReplanCommand toCommand(JsonNode payload) {
        return new ReplanCommand(
                UUID.fromString(text(payload, "routeId")),
                UUID.fromString(text(payload, "campId")),
                Instant.parse(text(payload, "detectedAt")),
                number(payload, "deviationSeconds"));
    }

    private static long number(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || !node.isNumber()) {
            throw new ValidationException("delivery.at-risk 에 %s 가 없습니다".formatted(field),
                    Map.of("field", field));
        }
        return node.asLong();
    }

    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            throw new ValidationException("delivery.at-risk 에 %s 가 없습니다".formatted(field),
                    Map.of("field", field));
        }
        return node.asString();
    }
}
