package com.dawnline.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Parcel — 소포 제원 (DESIGN.md §4.3, §5.1)")
class ParcelTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 무게가_0_이하면_거부한다(int weightG) {
        assertThatThrownBy(() -> new Parcel(weightG, 8000, false, false))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("weightG");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 부피가_0_이하면_거부한다(int volumeCm3) {
        assertThatThrownBy(() -> new Parcel(1200, volumeCm3, false, false))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("volumeCm3");
    }

    @Test
    void 무게_상한_1톤을_넘으면_거부한다() {
        assertThatThrownBy(() -> new Parcel(1_000_001, 8000, false, false))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("weightG");
    }

    @Test
    void 부피_상한_1세제곱미터를_넘으면_거부한다() {
        assertThatThrownBy(() -> new Parcel(1200, 1_000_001, false, false))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("volumeCm3");
    }

    @Test
    void 상한값_자체는_허용한다() {
        assertThat(new Parcel(1_000_000, 1_000_000, false, false).weightG()).isEqualTo(1_000_000);
    }

    @ParameterizedTest
    @CsvSource({
        "false, false, false",
        "true,  false, true",
        "false, true,  true",
        "true,  true,  true",
    })
    void 냉장이거나_위험물이면_특수_차량이_필요하다(boolean cold, boolean hazmat, boolean expected) {
        assertThat(new Parcel(1200, 8000, cold, hazmat).needsSpecialVehicle()).isEqualTo(expected);
    }
}
