package com.dawnline.dispatch.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.ValidationException;
import com.dawnline.messaging.contract.EventContracts;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code order.cancelled.v1} 읽기 (§6.10).
 *
 * <p>계약 예시로 검증한다(계약 README §3) — 소비자가 자기 손으로 만든 JSON 으로만 테스트하면
 * 발행자가 실제로 내는 모양과 어긋나도 초록이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OrderCancelledPayloadTest {

    private static final EventContracts CONTRACTS = EventContracts.load();

    private static JsonNode example() {
        return CONTRACTS.readTree(
                CONTRACTS.contractsDirectory().resolve("examples")
                        .resolve("order.cancelled.v1.example.json"))
                .get("payload");
    }

    @Test
    void 계약_예시에서_주문과_시각을_읽는다() {
        JsonNode payload = example();

        assertThat(OrderCancelledPayload.orderId(payload))
                .isEqualTo(UUID.fromString(payload.get("orderId").asString()));
        assertThat(OrderCancelledPayload.cancelledAt(payload))
                .isEqualTo(Instant.parse(payload.get("cancelledAt").asString()));
    }

    @Test
    void 시각은_우리가_처리한_때가_아니라_사건이_일어난_때다() {
        // §4.2 occurredAt 과 같은 값이다. 처리 시각을 쓰면 컨슈머 랙이 "언제 취소됐나" 의
        // 답을 오염시킨다.
        JsonNode payload = example();

        assertThat(OrderCancelledPayload.cancelledAt(payload)).isBefore(Instant.now());
    }

    @Test
    void 주문_id_가_없으면_거부한다() {
        JsonNode payload = CONTRACTS.json().readTree("{\"cancelledAt\":\"2026-09-06T01:00:00Z\"}");

        assertThatThrownBy(() -> OrderCancelledPayload.orderId(payload))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("orderId");
    }

    @Test
    void 취소_시각이_없으면_거부한다() {
        JsonNode payload = CONTRACTS.json()
                .readTree("{\"orderId\":\"01a04dad-80da-7f6e-a63a-e91c103516b0\"}");

        assertThatThrownBy(() -> OrderCancelledPayload.cancelledAt(payload))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cancelledAt");
    }
}
