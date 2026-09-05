package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.Money;
import com.dawnline.common.error.ValidationException;

/**
 * 차량 한 대의 비용 파라미터 (DESIGN.md §6.1 비용식, §6.4).
 *
 * <p>값은 {@code vehicles} 테이블에서 온다 — <strong>코드에 상수를 두지 않는다</strong>(§6.4).
 *
 * @param fixed    라우트 하나를 굴리는 고정비
 * @param perKm    km 당 비용
 * @param perMin   분당 비용
 */
public record VehicleCost(Money fixed, Money perKm, Money perMin) {

    public VehicleCost {
        requireNonNegative(fixed, "fixed");
        requireNonNegative(perKm, "perKm");
        requireNonNegative(perMin, "perMin");
    }

    /** KRW 정수로 바로 만든다. */
    public static VehicleCost krw(long fixed, long perKm, long perMin) {
        return new VehicleCost(Money.krw(fixed), Money.krw(perKm), Money.krw(perMin));
    }

    private static void requireNonNegative(Money value, String field) {
        if (value == null) {
            throw ValidationException.field(field, null, "비용 파라미터는 필수입니다");
        }
        if (value.krw() < 0L) {
            throw ValidationException.field(field, value.krw(), "비용 파라미터는 음수일 수 없습니다");
        }
    }
}
