package com.dawnline.fulfillment.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.domain.FcFallbackReason;
import com.dawnline.fulfillment.domain.FulfillmentOrder;
import com.dawnline.fulfillment.domain.FulfillmentOrderStatus;
import com.dawnline.fulfillment.domain.UnserviceableReason;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@code fulfillment_orders} 왕복 매핑 (ADR-022).
 *
 * <p>DB 는 {@code FulfillmentPersistenceIT} 가 본다. 여기서 보는 것은 <strong>컬럼 하나를
 * 빠뜨렸는가</strong>다 — 16개 필드를 손으로 옮기는 코드라 빠뜨리기 쉽고, 빠뜨리면 그 값이
 * 조용히 사라진다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FulfillmentOrderEntityTest {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID WAVE_ID = UUID.randomUUID();
    private static final UUID CAMP_ID = UUID.randomUUID();
    private static final UUID FC_ID = UUID.randomUUID();
    private static final UUID ZONE_ID = UUID.randomUUID();
    private static final Instant CUTOFF = Instant.parse("2026-09-05T01:00:00Z");
    private static final Instant AT = Instant.parse("2026-09-05T00:30:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(
            Instant.parse("2026-09-05T15:00:00Z"), Instant.parse("2026-09-05T22:00:00Z"));

    private static FulfillmentOrder planned() {
        return FulfillmentOrder.planned(ORDER_ID, EVENT_ID, WAVE_ID, CAMP_ID, FC_ID, ZONE_ID,
                CUTOFF, WINDOW, true, FcFallbackReason.INVENTORY, AT);
    }

    @Test
    void 계획된_주문이_손실_없이_왕복한다() {
        FulfillmentOrder restored = FulfillmentOrderEntity.from(planned()).toDomain();

        assertThat(restored.orderId()).isEqualTo(ORDER_ID);
        assertThat(restored.status()).isEqualTo(FulfillmentOrderStatus.PLANNED);
        assertThat(restored.waveId()).contains(WAVE_ID);
        assertThat(restored.campId()).contains(CAMP_ID);
        assertThat(restored.fcId()).contains(FC_ID);
        assertThat(restored.zoneId()).contains(ZONE_ID);
        assertThat(restored.cutoffAt()).contains(CUTOFF);
        assertThat(restored.promisedWindow()).contains(WINDOW);
        assertThat(restored.promiseRevised()).isTrue();
        assertThat(restored.fcFallbackReason()).contains(FcFallbackReason.INVENTORY);
        assertThat(restored.placedEventId()).contains(EVENT_ID);
        assertThat(restored.unserviceableReason()).isEmpty();
        assertThat(restored.cancelledAt()).isEmpty();
        assertThat(restored.createdAt()).isEqualTo(AT);
        assertThat(restored.updatedAt()).isEqualTo(AT);
    }

    @Test
    void 배차_불가_주문이_손실_없이_왕복한다() {
        FulfillmentOrder order = FulfillmentOrder.unserviceable(
                ORDER_ID, EVENT_ID, UnserviceableReason.NO_ELIGIBLE_FC, CAMP_ID, AT);

        FulfillmentOrder restored = FulfillmentOrderEntity.from(order).toDomain();

        assertThat(restored.status()).isEqualTo(FulfillmentOrderStatus.UNSERVICEABLE);
        assertThat(restored.unserviceableReason()).contains(UnserviceableReason.NO_ELIGIBLE_FC);
        assertThat(restored.campId()).contains(CAMP_ID);
        assertThat(restored.waveId()).isEmpty();
        assertThat(restored.promisedWindow()).as("약속창 두 컬럼이 모두 비면 창도 비어야 한다").isEmpty();
    }

    @Test
    void 취소_선착은_placed_event_id_가_빈_채로_왕복한다() {
        FulfillmentOrder order = FulfillmentOrder.cancelledBeforePlaced(ORDER_ID, AT);

        FulfillmentOrder restored = FulfillmentOrderEntity.from(order).toDomain();

        assertThat(restored.status()).isEqualTo(FulfillmentOrderStatus.CANCELLED);
        assertThat(restored.placedEventId()).isEmpty();
        assertThat(restored.cancelledAt()).contains(AT);
        assertThat(restored.ignoresPlaced()).isTrue();
    }

    @Test
    void 취소가_웨이브_소속과_판정_결과를_지우지_않는다() {
        // applyStateOf 가 일부 컬럼만 옮기면 여기서 값이 사라진다. "어느 웨이브에 있다가
        // 취소됐나" 가 이 표를 만든 이유다(ADR-022).
        FulfillmentOrder order = planned();
        FulfillmentOrderEntity entity = FulfillmentOrderEntity.from(order);
        Instant cancelledAt = CUTOFF.plusSeconds(30);
        order.cancel(cancelledAt);

        entity.applyStateOf(order);
        FulfillmentOrder restored = entity.toDomain();

        assertThat(restored.status()).isEqualTo(FulfillmentOrderStatus.CANCELLED);
        assertThat(restored.waveId()).contains(WAVE_ID);
        assertThat(restored.fcId()).contains(FC_ID);
        assertThat(restored.promisedWindow()).contains(WINDOW);
        assertThat(restored.cancelledAt()).contains(cancelledAt);
        assertThat(restored.createdAt()).as("접수 시각은 그대로다").isEqualTo(AT);
        assertThat(restored.updatedAt()).as("보존 정리의 기준은 옮겨간다").isEqualTo(cancelledAt);
    }

    @Test
    void 약속_개정이_창과_웨이브를_함께_옮긴다() {
        FulfillmentOrder order = FulfillmentOrder.planned(ORDER_ID, EVENT_ID, WAVE_ID, CAMP_ID,
                FC_ID, ZONE_ID, CUTOFF, WINDOW, false, null, AT);
        FulfillmentOrderEntity entity = FulfillmentOrderEntity.from(order);
        UUID nextWave = UUID.randomUUID();
        TimeWindow revised = new TimeWindow(
                Instant.parse("2026-09-06T05:00:00Z"), Instant.parse("2026-09-06T11:00:00Z"));

        order.revisePromise(revised, nextWave, CUTOFF.plusSeconds(120));
        entity.applyStateOf(order);

        FulfillmentOrder restored = entity.toDomain();
        assertThat(restored.promisedWindow()).contains(revised);
        assertThat(restored.waveId()).contains(nextWave);
        assertThat(restored.promiseRevised()).isTrue();
    }

    @Test
    void 다른_주문의_상태는_반영하지_않는다() {
        FulfillmentOrderEntity entity = FulfillmentOrderEntity.from(planned());
        FulfillmentOrder other = FulfillmentOrder.cancelledBeforePlaced(UUID.randomUUID(), AT);

        assertThatThrownBy(() -> entity.applyStateOf(other))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 새_엔티티는_버전이_0_이고_상태를_노출한다() {
        FulfillmentOrderEntity entity = FulfillmentOrderEntity.from(planned());

        assertThat(entity.orderId()).isEqualTo(ORDER_ID);
        assertThat(entity.status()).isEqualTo(FulfillmentOrderStatus.PLANNED);
        assertThat(entity.version()).isZero();
    }
}
