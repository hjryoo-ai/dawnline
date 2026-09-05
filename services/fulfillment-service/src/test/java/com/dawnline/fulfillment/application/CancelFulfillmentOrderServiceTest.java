package com.dawnline.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.application.port.in.CancelFulfillmentOrderUseCase.CancelOutcome;
import com.dawnline.fulfillment.domain.FulfillmentOrder;
import com.dawnline.fulfillment.domain.FulfillmentOrderStatus;
import com.dawnline.fulfillment.domain.UnserviceableReason;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** {@code order.cancelled} 처리 (ADR-022, ADR-025). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("CancelFulfillmentOrderService — 웨이브를 건드리지 않는다")
class CancelFulfillmentOrderServiceTest {

    private static final Instant AT = Instant.parse("2026-09-06T02:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-09-06T01:00:00Z");

    private final InMemoryFulfillmentRepositories repositories = new InMemoryFulfillmentRepositories();
    private final CancelFulfillmentOrderService service =
            new CancelFulfillmentOrderService(repositories.orderRepository());

    private UUID planned() {
        UUID orderId = UUID.randomUUID();
        repositories.orderRepository().insertIfAbsent(FulfillmentOrder.planned(orderId,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), CUTOFF,
                new TimeWindow(CUTOFF, CUTOFF.plusSeconds(3600)), false, null, CUTOFF));
        return orderId;
    }

    @Test
    void 계획된_주문을_취소한다() {
        UUID orderId = planned();

        assertThat(service.cancel(orderId, AT)).isEqualTo(CancelOutcome.CANCELLED);
        assertThat(repositories.order(orderId)).get()
                .extracting(FulfillmentOrder::status).isEqualTo(FulfillmentOrderStatus.CANCELLED);
    }

    @Test
    void 취소가_먼저_오면_placed_event_id_없는_행을_만든다() {
        // 별도 마커 테이블이 필요 없는 이유다. 뒤늦게 오는 order.placed 는 축 규칙이 무시한다.
        UUID orderId = UUID.randomUUID();

        assertThat(service.cancel(orderId, AT)).isEqualTo(CancelOutcome.CANCELLED_BEFORE_PLACED);
        FulfillmentOrder saved = repositories.order(orderId).orElseThrow();
        assertThat(saved.placedEventId()).isEmpty();
        assertThat(saved.ignoresPlaced()).isTrue();
    }

    @Test
    void 중복_취소는_사실을_바꾸지_않는다() {
        // 예외로 만들면 정상 흐름이 사고처럼 보인다.
        UUID orderId = planned();
        service.cancel(orderId, AT);

        assertThat(service.cancel(orderId, AT.plusSeconds(10))).isEqualTo(CancelOutcome.ALREADY_CANCELLED);
        assertThat(repositories.order(orderId)).get()
                .extracting(order -> order.cancelledAt().orElseThrow()).isEqualTo(AT);
    }

    @Test
    void 배차_불가_주문도_취소된다() {
        UUID orderId = UUID.randomUUID();
        repositories.orderRepository().insertIfAbsent(FulfillmentOrder.unserviceable(
                orderId, UUID.randomUUID(), UnserviceableReason.NO_ELIGIBLE_FC, UUID.randomUUID(), CUTOFF));

        assertThat(service.cancel(orderId, AT)).isEqualTo(CancelOutcome.CANCELLED);
    }

    @Test
    void 취소는_웨이브_소속을_지우지_않는다() {
        // "어느 웨이브에 있다가 취소됐나" 가 조사 대상이다 (ADR-022).
        UUID orderId = planned();
        UUID waveId = repositories.order(orderId).orElseThrow().waveId().orElseThrow();

        service.cancel(orderId, AT);

        assertThat(repositories.order(orderId)).get()
                .extracting(order -> order.waveId().orElseThrow()).isEqualTo(waveId);
    }
}
