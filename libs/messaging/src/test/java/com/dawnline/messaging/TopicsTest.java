package com.dawnline.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.dawnline.common.Ids;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 토픽 명명 규칙 (DESIGN.md §4.1). */
class TopicsTest {

    @ParameterizedTest
    @CsvSource({
        "order.placed,          1, dawnline.order.placed.v1",
        "fulfillment.planned,   1, dawnline.fulfillment.planned.v1",
        "wave.closed,           1, dawnline.wave.closed.v1",
        "route.assigned,        1, dawnline.route.assigned.v1",
        "delivery.at-risk,      1, dawnline.delivery.at-risk.v1",
        "order.placed,          2, dawnline.order.placed.v2",
    })
    void forEvent_설계서_토픽_목록과_일치한다(String eventType, int version, String expected) {
        assertThat(Topics.forEvent(eventType, version)).isEqualTo(expected);
    }

    @Test
    void forEvent_schemaVersion_0_예외() {
        assertThatIllegalArgumentException().isThrownBy(() -> Topics.forEvent("order.placed", 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OrderPlaced", "order", "order.", ".placed", "order..placed", "order.Placed",
        "order.placed ", "order_placed", "1order.placed", "order.placed.", "-order.placed"})
    void requireValidEventType_형식이_어긋나면_예외(String eventType) {
        // 이 검사가 없으면 outbox INSERT 는 통과하고 릴레이가 봉투를 만들 때 터진다 → 독약 행.
        assertThatIllegalArgumentException().isThrownBy(() -> Topics.requireValidEventType(eventType));
    }

    @Test
    void requireValidEventType_봉투_불변식과_같은_규칙을_쓴다() {
        // EventEnvelope 가 이 메서드를 그대로 호출한다. 규칙이 갈라지면 독약 행이 다시 생긴다.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventEnvelope<>(Ids.newId(), "OrderPlaced", 1, Instant.EPOCH,
                        "order-service", "key", null, "payload"));
        assertThat(Topics.requireValidEventType("delivery.at-risk")).isEqualTo("delivery.at-risk");
    }

    @Test
    void forEvent_eventType_형식이_어긋나면_예외() {
        assertThatIllegalArgumentException().isThrownBy(() -> Topics.forEvent("OrderPlaced", 1));
    }

    @Test
    void dlqFor_접미사를_붙인다() {
        assertThat(Topics.dlqFor("dawnline.order.placed.v1")).isEqualTo("dawnline.order.placed.v1.dlq");
    }

    @Test
    void dlqFor_이미_DLQ면_그대로_둔다() {
        // DLQ 소비자가 실패했을 때 dlq.dlq 를 만들지 않는다.
        assertThat(Topics.dlqFor("dawnline.order.placed.v1.dlq")).isEqualTo("dawnline.order.placed.v1.dlq");
    }

    @Test
    void isDlq_판별한다() {
        assertThat(Topics.isDlq("dawnline.order.placed.v1.dlq")).isTrue();
        assertThat(Topics.isDlq("dawnline.order.placed.v1")).isFalse();
    }
}
