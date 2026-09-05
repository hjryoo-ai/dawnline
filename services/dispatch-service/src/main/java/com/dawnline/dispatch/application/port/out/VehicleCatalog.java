package com.dawnline.dispatch.application.port.out;

import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 캠프의 가용 차량 (DESIGN.md §5.3 {@code vehicles}). */
public interface VehicleCatalog {

    /**
     * 이 캠프의 활성 차량들.
     *
     * <p>{@code shift_start}/{@code shift_end} 는 <strong>벽시계</strong>({@code TIME})이고
     * {@link VehicleSpec#shift()} 는 {@link Instant} 다. 계획 대상 날짜에 붙이는 일을 여기서
     * 한다 — 순수 함수는 "몇 시" 가 아니라 "언제" 만 다룬다(불변규칙 12).
     *
     * @param campId  캠프 id
     * @param planFor 계획 대상 시각. 이 시각의 날짜에 근무창을 붙인다
     */
    List<VehicleSpec> availableAt(UUID campId, Instant planFor);
}
