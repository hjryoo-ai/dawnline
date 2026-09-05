package com.dawnline.dispatch.domain.optimizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.ValidationException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CapacityTest {

    private static final Capacity CAPACITY = new Capacity(1_000, 2_000);

    @Test
    void 용량과_정확히_같은_적재는_실을_수_있다() {
        // 경계는 포함이다. 배제로 두면 정확히 가득 찬 차가 미배정을 만든다.
        assertThat(CAPACITY.admits(new Parcel(1_000, 2_000, false, false))).isTrue();
    }

    @Test
    void 중량만_넘어도_실을_수_없다() {
        assertThat(CAPACITY.admits(new Parcel(1_001, 0, false, false))).isFalse();
    }

    @Test
    void 부피만_넘어도_실을_수_없다() {
        assertThat(CAPACITY.admits(new Parcel(0, 2_001, false, false))).isFalse();
    }

    @Test
    void 용량이_0_인_차량은_만들_수_없다() {
        assertThatThrownBy(() -> new Capacity(0, 1)).isInstanceOf(ValidationException.class);
    }
}
