package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.TimeWindow;
import java.util.Objects;

/**
 * 계획에 쓰이는 차량 한 대 (DESIGN.md §6.2).
 *
 * <p>근무창이 {@code ShiftWindow} 가 아니라 {@link TimeWindow} 인 이유는 §6.2 에 적었다 — 같은
 * 뜻의 타입을 하나 더 두면 둘 중 하나에만 경계 규칙이 붙는다.
 *
 * <p>{@code vehicles} 테이블의 {@code shift_start}/{@code shift_end} 는 {@code TIME}(벽시계)이고
 * 여기의 {@code shift} 는 <strong>계획 대상 날짜에 붙인 {@link java.time.Instant}</strong> 다.
 * 붙이는 일은 어댑터가 한다 — 순수 함수는 "몇 시" 가 아니라 "언제" 만 다룬다(불변규칙 12).
 *
 * @param id       차량 id
 * @param capacity 적재 용량
 * @param attrs    속성(차종·냉장·위험물)
 * @param shift    근무창. 이 창 안에서 출발하고 복귀해야 한다 (§6.3 {@code SHIFT_WINDOW})
 * @param cost     비용 파라미터
 */
public record VehicleSpec(VehicleId id, Capacity capacity, VehicleAttrs attrs, TimeWindow shift,
        VehicleCost cost) {

    public VehicleSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(capacity, "capacity");
        Objects.requireNonNull(attrs, "attrs");
        Objects.requireNonNull(shift, "shift");
        Objects.requireNonNull(cost, "cost");
    }
}
