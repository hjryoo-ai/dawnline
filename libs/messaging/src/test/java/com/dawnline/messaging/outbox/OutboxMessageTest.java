package com.dawnline.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link OutboxMessage} — 발행 요청의 불변식 (DESIGN.md §4.1, §4.4).
 *
 * <p>여기서 막는 값은 <em>릴레이가 봉투를 만들 때 터질</em> 값이다. INSERT 를 통과시키면
 * 그 행은 {@code created_at} 순서상 맨 앞에 서서 뒤의 모든 이벤트를 영구히 막는다.
 */
class OutboxMessageTest {

    private static final UUID AGGREGATE_ID = UUID.fromString("01a04dad-80da-7f6e-a63a-e91c10350000");

    /**
     * 페이로드 예시.
     *
     * @param orderId 주문 id
     */
    record OrderPlaced(String orderId) {
    }

    @ParameterizedTest
    @ValueSource(strings = {"OrderPlaced", "order", "order.", ".placed", "order..placed", "order.Placed",
        "order_placed", "1order.placed"})
    void eventType_형식이_어긋나면_INSERT_전에_거부한다(String eventType) {
        // 길이만 봤을 때는 전부 통과하던 값들이다. 형식까지 봐야 독약 행이 생기지 않는다.
        assertThatIllegalArgumentException().isThrownBy(
                () -> OutboxMessage.keyedByAggregate("Order", AGGREGATE_ID, eventType, 1, new OrderPlaced("x")));
    }

    @Test
    void eventType_형식이_어긋나면_정식_생성자도_거부한다() {
        // of() 를 우회해 topic 을 직접 주더라도 막혀야 한다.
        assertThatIllegalArgumentException().isThrownBy(() -> new OutboxMessage(
                "Order", AGGREGATE_ID, "OrderPlaced", 1, "dawnline.order.placed.v1", "key",
                new OrderPlaced("x")));
    }

    @Test
    void of_토픽을_4_1_규칙으로_만든다() {
        OutboxMessage message =
                OutboxMessage.of("Order", AGGREGATE_ID, "delivery.at-risk", 2, "key", new OrderPlaced("x"));

        assertThat(message.topic()).isEqualTo("dawnline.delivery.at-risk.v2");
        assertThat(message.partitionKey()).isEqualTo("key");
    }

    @Test
    void keyedByAggregate_파티션_키가_애그리거트_id다() {
        OutboxMessage message =
                OutboxMessage.keyedByAggregate("Order", AGGREGATE_ID, "order.placed", 1, new OrderPlaced("x"));

        assertThat(message.partitionKey()).isEqualTo(AGGREGATE_ID.toString());
    }

    @Test
    void schemaVersion_0_예외() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> OutboxMessage.keyedByAggregate("Order", AGGREGATE_ID, "order.placed", 0,
                        new OrderPlaced("x")));
    }

    @Test
    void aggregateType_길이_상한을_넘으면_예외() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> OutboxMessage.keyedByAggregate("A".repeat(33), AGGREGATE_ID, "order.placed", 1,
                        new OrderPlaced("x")));
    }
}
