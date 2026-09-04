package com.dawnline.order.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.messaging.outbox.OutboxAppender;
import com.dawnline.messaging.outbox.OutboxMessage;
import com.dawnline.order.domain.DeliveryAddress;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.Parcel;
import com.dawnline.order.domain.PromisedWindow;
import com.dawnline.order.domain.ServiceTier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

/**
 * {@code order.cancelled} 계약 테스트 (CLAUDE.md 불변규칙 8).
 *
 * <p>{@code OrderPlacedContractTest} 와 같은 이유로 스키마 파일이 아니라 <strong>실제로 발행되는
 * 페이로드</strong>를 검증한다.
 */
@DisplayName("order.cancelled — 발행되는 페이로드가 계약을 지킨다")
class OrderCancelledContractTest {

    private static final Instant PLACED_AT = Instant.parse("2026-09-03T00:00:00Z");
    private static final Instant CANCELLED_AT = Instant.parse("2026-09-03T01:00:00Z");
    private static final EventContracts CONTRACTS = EventContracts.load();

    private static Order cancelledOrder(OrderStatus from) {
        Order order = Order.place(Ids.newId(), Ids.newId(), ServiceTier.DAWN,
                DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GeoPoint.of(37.4979, 127.0276)),
                PromisedWindow.of(PLACED_AT.plus(Duration.ofHours(15)), PLACED_AT.plus(Duration.ofHours(22)),
                        ServiceTier.DAWN),
                new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1001", 2)), PLACED_AT);
        if (from == OrderStatus.PLANNED) {
            order.markPlanned(PLACED_AT.plusSeconds(60));
        }
        order.cancel(CANCELLED_AT);
        return order;
    }

    private static OutboxMessage published(Order order, OrderStatus previousStatus, String reason) {
        OutboxAppender appender = mock(OutboxAppender.class);
        new OutboxOrderEvents(appender).cancelled(order, previousStatus, reason);

        ArgumentCaptor<OutboxMessage> message = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(appender).append(message.capture());
        return message.getValue();
    }

    @Test
    void 발행되는_페이로드가_스키마를_통과한다() {
        JsonNode payload = CONTRACTS.json()
                .toTree(published(cancelledOrder(OrderStatus.PLACED), OrderStatus.PLACED, "고객 요청").payload());

        CONTRACTS.validatePayload(OrderCancelledPayload.EVENT_TYPE,
                OrderCancelledPayload.SCHEMA_VERSION, payload);
    }

    @Test
    void 사유가_없어도_계약을_지킨다() {
        // reason 은 선택 필드다. null 을 그대로 내보내면 스키마(type: string)가 거부하므로
        // EventJson 의 NON_NULL 설정이 그 필드를 아예 빼야 한다.
        JsonNode payload = CONTRACTS.json()
                .toTree(published(cancelledOrder(OrderStatus.PLACED), OrderStatus.PLACED, null).payload());

        CONTRACTS.validatePayload(OrderCancelledPayload.EVENT_TYPE,
                OrderCancelledPayload.SCHEMA_VERSION, payload);
        assertThat(payload.has("reason")).isFalse();
    }

    @Test
    void 취소_직전_상태를_싣는다() {
        // PLACED 취소와 PLANNED 취소는 소비자가 할 일이 다르다(웨이브에서 빼야 하는가).
        JsonNode fromPlanned = CONTRACTS.json()
                .toTree(published(cancelledOrder(OrderStatus.PLANNED), OrderStatus.PLANNED, null).payload());

        assertThat(fromPlanned.get("previousStatus").asString()).isEqualTo("PLANNED");
    }

    @Test
    void 취소_시각은_애그리거트의_전이_시각이다() {
        // 발행 시각이 아니라 사건 시각이다 (§4.2).
        JsonNode payload = CONTRACTS.json()
                .toTree(published(cancelledOrder(OrderStatus.PLACED), OrderStatus.PLACED, null).payload());

        assertThat(payload.get("cancelledAt").asString()).isEqualTo(CANCELLED_AT.toString());
    }

    @Test
    void 토픽과_파티션_키가_규칙대로다() {
        Order order = cancelledOrder(OrderStatus.PLACED);

        OutboxMessage message = published(order, OrderStatus.PLACED, null);

        assertThat(message.topic()).isEqualTo("dawnline.order.cancelled.v1");
        // §4.5 — order.placed 와 같은 파티션이어야 두 이벤트의 순서가 보장된다.
        assertThat(message.partitionKey()).isEqualTo(order.id().toString());
        assertThat(message.aggregateType()).isEqualTo("order");
    }
}
