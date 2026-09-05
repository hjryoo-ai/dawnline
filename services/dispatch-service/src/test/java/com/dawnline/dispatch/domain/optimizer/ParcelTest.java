package com.dawnline.dispatch.domain.optimizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.ValidationException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ParcelTest {

    @Test
    void 통합하면_중량과_부피는_더해진다() {
        Parcel merged = new Parcel(1_000, 2_000, false, false)
                .plus(new Parcel(500, 800, false, false));

        assertThat(merged.weightG()).isEqualTo(1_500);
        assertThat(merged.volumeCm3()).isEqualTo(2_800);
    }

    @Test
    void 통합하면_냉장과_위험물은_OR_다() {
        // 한 건이라도 냉장이면 그 stop 전체가 냉장 차량을 요구한다 (§6.3 VEHICLE_ATTRIBUTE_MATCH).
        Parcel merged = new Parcel(1_000, 2_000, false, false)
                .plus(new Parcel(500, 800, true, false));

        assertThat(merged.requiresCold()).isTrue();
        assertThat(merged.hazmat()).isFalse();
    }

    @Test
    void 합이_int_를_넘으면_즉시_실패한다() {
        // 조용히 음수가 되면 용량 검사가 통과해 버린다.
        Parcel huge = new Parcel(Integer.MAX_VALUE, 1, false, false);

        assertThatThrownBy(() -> huge.plus(new Parcel(1, 1, false, false)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void 음수_중량은_만들_수_없다() {
        assertThatThrownBy(() -> new Parcel(-1, 0, false, false))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 빈_화물은_누적의_항등원이다() {
        Parcel parcel = new Parcel(1_000, 2_000, true, true);

        assertThat(Parcel.EMPTY.plus(parcel)).isEqualTo(parcel);
        assertThat(parcel.plus(Parcel.EMPTY)).isEqualTo(parcel);
    }
}
