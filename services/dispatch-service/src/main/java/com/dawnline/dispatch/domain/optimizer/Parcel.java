package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.error.ValidationException;

/**
 * 화물의 물리 속성 (DESIGN.md §6.2).
 *
 * <p>주문 하나의 것일 수도 있고 {@link Stop} 으로 통합된 여럿의 합일 수도 있다. 통합에서
 * 중량·부피는 더하고 <strong>냉장·위험물은 OR</strong> 이다 — 한 건이라도 냉장이 필요하면 그 stop
 * 전체가 냉장 차량을 요구한다(§6.3 {@code VEHICLE_ATTRIBUTE_MATCH}).
 *
 * @param weightG      중량(g)
 * @param volumeCm3    부피(㎤)
 * @param requiresCold 냉장이 필요한가
 * @param hazmat       위험물인가
 */
public record Parcel(int weightG, int volumeCm3, boolean requiresCold, boolean hazmat) {

    /** 빈 화물. 누적의 시작점이다. */
    public static final Parcel EMPTY = new Parcel(0, 0, false, false);

    public Parcel {
        if (weightG < 0) {
            throw ValidationException.field("weightG", weightG, "중량은 음수일 수 없습니다");
        }
        if (volumeCm3 < 0) {
            throw ValidationException.field("volumeCm3", volumeCm3, "부피는 음수일 수 없습니다");
        }
    }

    /**
     * 두 화물을 합친다. 중량·부피는 더하고 속성은 OR 다.
     *
     * <p>합이 {@code int} 를 넘으면 즉시 실패한다 — 조용히 음수가 되면 용량 검사가 통과해 버린다.
     *
     * @param other 더할 화물
     */
    public Parcel plus(Parcel other) {
        return new Parcel(
                Math.addExact(weightG, other.weightG),
                Math.addExact(volumeCm3, other.volumeCm3),
                requiresCold || other.requiresCold,
                hazmat || other.hazmat);
    }
}
