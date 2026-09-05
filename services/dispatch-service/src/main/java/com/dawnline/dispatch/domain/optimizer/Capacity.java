package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.error.ValidationException;

/**
 * 차량 적재 용량 (DESIGN.md §6.2).
 *
 * @param maxWeightG   최대 중량(g)
 * @param maxVolumeCm3 최대 부피(㎤)
 */
public record Capacity(int maxWeightG, int maxVolumeCm3) {

    public Capacity {
        if (maxWeightG <= 0) {
            throw ValidationException.field("maxWeightG", maxWeightG, "최대 중량은 양수여야 합니다");
        }
        if (maxVolumeCm3 <= 0) {
            throw ValidationException.field("maxVolumeCm3", maxVolumeCm3, "최대 부피는 양수여야 합니다");
        }
    }

    /**
     * 이 화물을 실을 수 있는가 (§6.3 {@code VEHICLE_CAPACITY}, 하드 룰).
     *
     * <p>경계는 <strong>포함</strong>이다 — 용량과 정확히 같은 적재는 실을 수 있다.
     *
     * @param load 실으려는 (누적) 화물
     */
    public boolean admits(Parcel load) {
        return load.weightG() <= maxWeightG && load.volumeCm3() <= maxVolumeCm3;
    }
}
