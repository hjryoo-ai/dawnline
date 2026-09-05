package com.dawnline.fulfillment.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.application.port.in.PlacedOrderSnapshot;
import com.dawnline.fulfillment.domain.UnserviceableReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@code fulfillment.planned} 페이로드 (§4.3).
 *
 * <p>계약 파일과의 일치는 {@code EventContractsTest} 가 예시로 본다. 여기서 보는 것은
 * <strong>두 결과가 각각 무엇을 싣고 무엇을 비우는가</strong>다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FulfillmentPlannedPayloadTest {

    private static final Instant CUTOFF = Instant.parse("2026-09-06T01:00:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(CUTOFF, CUTOFF.plusSeconds(21600));

    private static PlacedOrderSnapshot snapshot() {
        return new PlacedOrderSnapshot(UUID.randomUUID(), UUID.randomUUID(), "SAME_DAY",
                new PlacedOrderSnapshot.Address("서울 강남구 테헤란로 1", "06236",
                        new GeoPoint(37.4979, 127.0276), "wydm7bc"),
                WINDOW,
                new PlacedOrderSnapshot.Parcel(1200, 8000, false, false),
                List.of(new PlacedOrderSnapshot.Item("SKU-00001", 1)),
                CUTOFF.minusSeconds(3600), CUTOFF);
    }

    @Test
    void 계획됨은_스냅샷을_되싣고_판정을_덧붙인다() {
        // 하류가 주문을 다시 묻지 않아도 되게 한다 (§4.3, 불변규칙 4).
        UUID fcId = UUID.randomUUID();
        UUID waveId = UUID.randomUUID();
        PlacedOrderSnapshot snapshot = snapshot();

        FulfillmentPlannedPayload payload = FulfillmentPlannedPayload.planned(snapshot, fcId,
                UUID.randomUUID(), UUID.randomUUID(), waveId, CUTOFF, WINDOW, true);

        assertThat(payload.outcome()).isEqualTo("PLANNED");
        assertThat(payload.orderId()).isEqualTo(snapshot.orderId());
        assertThat(payload.address().geohash7()).isEqualTo("wydm7bc");
        assertThat(payload.items()).singleElement().extracting(FulfillmentPlannedPayload.Item::sku)
                .isEqualTo("SKU-00001");
        assertThat(payload.fcId()).isEqualTo(fcId);
        assertThat(payload.waveId()).isEqualTo(waveId);
        assertThat(payload.promiseRevised()).isTrue();
        assertThat(payload.reason()).isNull();
    }

    @Test
    void 배차_불가는_promiseRevised_를_싣지_않는다() {
        // 배차되지 못한 주문에는 개정할 약속이 없다 (계약 README §4.5-1).
        FulfillmentPlannedPayload payload = FulfillmentPlannedPayload.unserviceable(
                snapshot(), UnserviceableReason.STALE_PLACED);

        assertThat(payload.outcome()).isEqualTo("UNSERVICEABLE");
        assertThat(payload.promiseRevised()).isNull();
        assertThat(payload.reason()).isEqualTo("STALE_PLACED");
        assertThat(payload.waveId()).isNull();
        assertThat(payload.campId()).isNull();
    }

    @Test
    void 배차_불가도_약속창은_접수_시점의_값을_그대로_싣는다() {
        FulfillmentPlannedPayload payload = FulfillmentPlannedPayload.unserviceable(
                snapshot(), UnserviceableReason.NO_ZONE_MATCH);

        assertThat(payload.promisedWindow().start()).isEqualTo(WINDOW.start());
        assertThat(payload.promisedWindow().end()).isEqualTo(WINDOW.end());
    }
}
