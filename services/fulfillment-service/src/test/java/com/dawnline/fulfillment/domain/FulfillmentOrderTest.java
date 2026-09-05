package com.dawnline.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.IllegalStateTransitionException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 주문 애그리거트 (ADR-022) — 특히 취소 선착이 축 규칙으로 흡수되는지. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FulfillmentOrderTest {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID WAVE_ID = UUID.randomUUID();
    private static final UUID CAMP_ID = UUID.randomUUID();
    private static final UUID FC_ID = UUID.randomUUID();
    private static final UUID ZONE_ID = UUID.randomUUID();
    private static final Instant CUTOFF = Instant.parse("2026-09-05T01:00:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(
            Instant.parse("2026-09-05T15:00:00Z"), Instant.parse("2026-09-05T22:00:00Z"));

    private static final Instant AT = Instant.parse("2026-09-05T00:30:00Z");

    private static FulfillmentOrder planned() {
        return FulfillmentOrder.planned(ORDER_ID, EVENT_ID, WAVE_ID, CAMP_ID, FC_ID, ZONE_ID,
                CUTOFF, WINDOW, false, null, AT);
    }

    @Test
    void 계획된_주문은_웨이브와_판정_결과를_함께_들고_있다() {
        // wave_orders 가 담지 못하던 것들이다 — 이 애그리거트를 만든 이유(ADR-022).
        FulfillmentOrder order = FulfillmentOrder.planned(ORDER_ID, EVENT_ID, WAVE_ID, CAMP_ID, FC_ID,
                ZONE_ID, CUTOFF, WINDOW, true, FcFallbackReason.COLD, AT);

        assertThat(order.status()).isEqualTo(FulfillmentOrderStatus.PLANNED);
        assertThat(order.waveId()).contains(WAVE_ID);
        assertThat(order.campId()).contains(CAMP_ID);
        assertThat(order.fcId()).contains(FC_ID);
        assertThat(order.zoneId()).contains(ZONE_ID);
        assertThat(order.cutoffAt()).contains(CUTOFF);
        assertThat(order.promisedWindow()).contains(WINDOW);
        assertThat(order.promiseRevised()).isTrue();
        assertThat(order.fcFallbackReason()).contains(FcFallbackReason.COLD);
        assertThat(order.placedEventId()).contains(EVENT_ID);
    }

    @Test
    void 배차_불가는_사유를_남긴다() {
        FulfillmentOrder order = FulfillmentOrder.unserviceable(
                ORDER_ID, EVENT_ID, UnserviceableReason.NO_COLD_FC, CAMP_ID, AT);

        assertThat(order.status()).isEqualTo(FulfillmentOrderStatus.UNSERVICEABLE);
        assertThat(order.unserviceableReason()).contains(UnserviceableReason.NO_COLD_FC);
        assertThat(order.waveId()).isEmpty();
        assertThat(order.campId()).contains(CAMP_ID);
    }

    @Test
    void 권역을_못_찾은_경우에는_캠프도_없다() {
        FulfillmentOrder order = FulfillmentOrder.unserviceable(
                ORDER_ID, EVENT_ID, UnserviceableReason.NO_ZONE_MATCH, null, AT);

        assertThat(order.campId()).isEmpty();
        assertThat(order.unserviceableReason()).contains(UnserviceableReason.NO_ZONE_MATCH);
    }

    // --- 취소 선착 ------------------------------------------------------------

    @Test
    void 취소_선착은_placedEventId_가_비어_있는_것으로_기록된다() {
        Instant at = Instant.parse("2026-09-05T02:00:00Z");

        FulfillmentOrder order = FulfillmentOrder.cancelledBeforePlaced(ORDER_ID, at);

        assertThat(order.status()).isEqualTo(FulfillmentOrderStatus.CANCELLED);
        assertThat(order.placedEventId()).isEmpty();
        assertThat(order.cancelledAt()).contains(at);
    }

    @Test
    void 취소_선착_뒤에_온_order_placed_는_무시된다() {
        // 별도 마커 테이블이 필요 없는 이유. 축 규칙이 그대로 처리한다 (ADR-022).
        FulfillmentOrder order = FulfillmentOrder.cancelledBeforePlaced(ORDER_ID, Instant.now());

        assertThat(order.ignoresPlaced()).isTrue();
    }

    @Test
    void 이미_판정된_주문에_다시_온_order_placed_도_무시된다() {
        // 중복은 processed_events 가 앞에서 거르지만, 다른 eventId 로 같은 주문이 다시 오면
        // 여기서 걸린다 — 두 번 계획하지 않는다.
        assertThat(planned().ignoresPlaced()).isTrue();
        assertThat(FulfillmentOrder.unserviceable(ORDER_ID, EVENT_ID,
                UnserviceableReason.OUT_OF_STOCK, CAMP_ID, AT).ignoresPlaced()).isTrue();
    }

    // --- 취소 후착 ------------------------------------------------------------

    @Test
    void 계획된_주문을_취소한다() {
        FulfillmentOrder order = planned();
        Instant at = Instant.parse("2026-09-05T03:00:00Z");

        order.cancel(at);

        assertThat(order.status()).isEqualTo(FulfillmentOrderStatus.CANCELLED);
        assertThat(order.cancelledAt()).contains(at);
        // 웨이브 소속은 지우지 않는다 — "어느 웨이브에 있다가 취소됐나" 가 조사 대상이다.
        assertThat(order.waveId()).contains(WAVE_ID);
    }

    @Test
    void 배차_불가_주문도_취소할_수_있다() {
        FulfillmentOrder order = FulfillmentOrder.unserviceable(
                ORDER_ID, EVENT_ID, UnserviceableReason.NO_ELIGIBLE_FC, CAMP_ID, AT);

        order.cancel(Instant.now());

        assertThat(order.status()).isEqualTo(FulfillmentOrderStatus.CANCELLED);
    }

    @Test
    void 이미_취소된_주문은_다시_취소할_수_없다() {
        FulfillmentOrder order = FulfillmentOrder.cancelledBeforePlaced(ORDER_ID, Instant.now());

        assertThatThrownBy(() -> order.cancel(Instant.now()))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    // --- 약속 개정 (ADR-020) --------------------------------------------------

    @Test
    void 약속을_개정하면_창과_웨이브가_함께_바뀐다() {
        FulfillmentOrder order = planned();
        UUID nextWave = UUID.randomUUID();
        TimeWindow revised = new TimeWindow(
                Instant.parse("2026-09-06T05:00:00Z"), Instant.parse("2026-09-06T11:00:00Z"));

        order.revisePromise(revised, nextWave, Instant.parse("2026-09-05T01:01:30Z"));

        assertThat(order.promisedWindow()).contains(revised);
        assertThat(order.waveId()).contains(nextWave);
        assertThat(order.promiseRevised()).isTrue();
    }

    @Test
    void 취소된_주문의_약속은_개정하지_않는다() {
        FulfillmentOrder order = FulfillmentOrder.cancelledBeforePlaced(ORDER_ID, Instant.now());

        assertThatThrownBy(() -> order.revisePromise(WINDOW, WAVE_ID, AT))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void 저장된_상태에서_모든_필드를_되살린다() {
        // 상태만 복원하면 취소 한 번에 나머지 컬럼이 날아간다 — 어댑터는 되살린 애그리거트를
        // 그대로 행에 반영하기 때문이다.
        Instant updated = Instant.parse("2026-09-05T02:00:00Z");

        FulfillmentOrder order = FulfillmentOrder.rehydrate(ORDER_ID, FulfillmentOrderStatus.PLANNED,
                WAVE_ID, CAMP_ID, FC_ID, ZONE_ID, CUTOFF, WINDOW, true, null, FcFallbackReason.TIER,
                EVENT_ID, null, AT, updated, 3);

        assertThat(order.orderId()).isEqualTo(ORDER_ID);
        assertThat(order.status()).isEqualTo(FulfillmentOrderStatus.PLANNED);
        assertThat(order.waveId()).contains(WAVE_ID);
        assertThat(order.campId()).contains(CAMP_ID);
        assertThat(order.fcId()).contains(FC_ID);
        assertThat(order.zoneId()).contains(ZONE_ID);
        assertThat(order.cutoffAt()).contains(CUTOFF);
        assertThat(order.promisedWindow()).contains(WINDOW);
        assertThat(order.promiseRevised()).isTrue();
        assertThat(order.fcFallbackReason()).contains(FcFallbackReason.TIER);
        assertThat(order.placedEventId()).contains(EVENT_ID);
        assertThat(order.createdAt()).isEqualTo(AT);
        assertThat(order.updatedAt()).isEqualTo(updated);
        assertThat(order.version()).isEqualTo(3);
    }

    @Test
    void 취소와_약속_개정은_updated_at_을_옮긴다() {
        // ADR-023 의 보존 기준이 이 값이다. 접수가 30일 전이라도 취소가 어제면 조사 대상은
        // 어제 사건이라 지우지 않는다.
        FulfillmentOrder order = planned();
        assertThat(order.updatedAt()).isEqualTo(AT);

        Instant cancelledAt = Instant.parse("2026-09-05T04:00:00Z");
        order.cancel(cancelledAt);

        assertThat(order.updatedAt()).isEqualTo(cancelledAt);
        assertThat(order.createdAt()).as("접수 시각은 그대로다").isEqualTo(AT);
    }
}
