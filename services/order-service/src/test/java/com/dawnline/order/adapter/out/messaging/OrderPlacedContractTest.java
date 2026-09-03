package com.dawnline.order.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.messaging.Topics;
import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.messaging.outbox.OutboxAppender;
import com.dawnline.messaging.outbox.OutboxMessage;
import com.dawnline.order.domain.DeliveryAddress;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.Parcel;
import com.dawnline.order.domain.PromisedWindow;
import com.dawnline.order.domain.ServiceTier;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

/**
 * {@code order.placed} 계약 테스트 (CLAUDE.md 불변규칙 8).
 *
 * <p>스키마 파일이 아니라 <strong>실제로 발행되는 페이로드</strong>를 검증한다. 예시 파일만 검증하면
 * "예시는 맞는데 코드가 다른 것을 보내는" 상태를 잡지 못한다. 그 상태는 소비자가 생기는 Phase 2 에서야
 * 드러나고, 그때는 이미 토픽에 잘못된 이벤트가 쌓여 있다.
 */
@DisplayName("order.placed — 발행되는 페이로드가 계약을 지킨다")
class OrderPlacedContractTest {

    private static final Instant PLACED_AT = Instant.parse("2026-09-03T00:00:00Z");
    private static final EventContracts CONTRACTS = EventContracts.load();

    private static Order order() {
        return Order.place(Ids.newId(), Ids.newId(), ServiceTier.DAWN,
                DeliveryAddress.of("서울특별시 강남구 강남대로 396, 101동 1203호", "06236",
                        GeoPoint.of(37.4979, 127.0276)),
                PromisedWindow.of(PLACED_AT.plus(Duration.ofHours(15)), PLACED_AT.plus(Duration.ofHours(22)),
                        ServiceTier.DAWN),
                new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1001", 2), new OrderItem((short) 2, "SKU-2043", 1)),
                PLACED_AT);
    }

    private static OutboxMessage published(Order order) {
        OutboxAppender appender = mock(OutboxAppender.class);
        new OutboxOrderEvents(appender).placed(order);

        ArgumentCaptor<OutboxMessage> message = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(appender).append(message.capture());
        return message.getValue();
    }

    private static List<String> fieldNames(JsonNode node) {
        return StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(node.propertyNames().iterator(), 0), false)
                .toList();
    }

    @Test
    void 발행되는_페이로드가_스키마를_통과한다() {
        JsonNode payload = CONTRACTS.json().toTree(published(order()).payload());

        CONTRACTS.validatePayload(OrderPlacedPayload.EVENT_TYPE, OrderPlacedPayload.SCHEMA_VERSION, payload);
    }

    @Test
    void 페이로드의_최상위_필드가_예시와_같다() {
        // 스키마는 additionalProperties: true 라 필드가 늘어도 통과한다. 예시와 맞춰 두면
        // 실수로 새 필드가 새어 나가는 것도 드러난다 (§4.7 — 같은 major 안에서는 의도적 추가만).
        JsonNode payload = CONTRACTS.json().toTree(published(order()).payload());
        Path example = CONTRACTS.contractsDirectory().resolve("examples/order.placed.v1.example.json");
        JsonNode expected = CONTRACTS.readTree(example).get("payload");

        assertThat(fieldNames(payload)).containsExactlyInAnyOrderElementsOf(fieldNames(expected));
        assertThat(fieldNames(payload.get("address")))
                .containsExactlyInAnyOrderElementsOf(fieldNames(expected.get("address")));
        assertThat(fieldNames(payload.get("parcel")))
                .containsExactlyInAnyOrderElementsOf(fieldNames(expected.get("parcel")));
        assertThat(fieldNames(payload.get("items").get(0)))
                .containsExactlyInAnyOrderElementsOf(fieldNames(expected.get("items").get(0)));
    }

    @Test
    void 토픽과_파티션_키가_규칙대로다() {
        Order order = order();

        OutboxMessage message = published(order);

        assertThat(message.topic()).isEqualTo(Topics.forEvent("order.placed", 1));
        assertThat(message.topic()).isEqualTo("dawnline.order.placed.v1");
        // §4.5 — 같은 주문의 이벤트는 같은 파티션으로 가야 순서가 보장된다.
        assertThat(message.partitionKey()).isEqualTo(order.id().toString());
        assertThat(message.aggregateType()).isEqualTo("order");
        assertThat(message.aggregateId()).isEqualTo(order.id());
        assertThat(message.schemaVersion()).isEqualTo(1);
    }

    @Test
    void 값이_애그리거트와_일치한다() {
        Order order = order();

        JsonNode payload = CONTRACTS.json().toTree(published(order).payload());

        assertThat(payload.get("orderId").asString()).isEqualTo(order.id().toString());
        assertThat(payload.get("customerId").asString()).isEqualTo(order.customerId().toString());
        assertThat(payload.get("serviceTier").asString()).isEqualTo("DAWN");
        assertThat(payload.get("address").get("geohash7").asString()).isEqualTo(order.address().geohash7());
        assertThat(payload.get("address").get("lat").asDouble()).isEqualTo(37.4979);
        assertThat(payload.get("promisedWindow").get("start").asString())
                .isEqualTo(order.promisedWindow().start().toString());
        assertThat(payload.get("items")).hasSize(2);
        assertThat(payload.get("items").get(1).get("sku").asString()).isEqualTo("SKU-2043");
    }

    @Test
    void 품목에_줄_번호를_싣지_않는다() {
        // lineNo 는 order_items 의 내부 키다. 소비자(fulfillment)는 SKU 와 수량만 쓴다(§5.2).
        JsonNode item = CONTRACTS.json().toTree(published(order()).payload()).get("items").get(0);

        assertThat(fieldNames(item)).containsExactlyInAnyOrder("sku", "qty");
    }
}
