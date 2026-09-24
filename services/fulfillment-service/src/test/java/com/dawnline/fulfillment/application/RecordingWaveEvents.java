package com.dawnline.fulfillment.application;

import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.application.port.in.PlacedOrderSnapshot;
import com.dawnline.fulfillment.application.port.out.FulfillmentEvents;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.UnserviceableReason;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.observability.MdcKeys;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/** {@code wave.closed} 만 기록한다 — 마감 테스트의 outbox 대역. 발행하는 순간의 MDC {@code waveId} 도 남긴다. */
final class RecordingWaveEvents implements FulfillmentEvents {

    final List<Wave> closed = new ArrayList<>();
    final List<@Nullable String> mdcWaveIds = new ArrayList<>();
    boolean fail;

    @Override
    public void planned(PlacedOrderSnapshot snapshot, UUID fcId, UUID campId, UUID zoneId, UUID waveId,
            Instant waveCutoffAt, TimeWindow window, boolean revised) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unserviceable(PlacedOrderSnapshot snapshot, UnserviceableReason reason) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void waveClosed(Wave wave, Camp camp) {
        if (fail) {
            throw new IllegalStateException("발행 실패");
        }
        closed.add(wave);
        mdcWaveIds.add(MDC.get(MdcKeys.WAVE_ID));
    }
}
