package com.dawnline.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.ValidationException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("PromisedWindow — 약속 배송창 (DESIGN.md §2.2, §8.1)")
class PromisedWindowTest {

    private static final Instant START = Instant.parse("2026-09-02T15:00:00Z"); // 익일 00:00 KST

    @ParameterizedTest
    @CsvSource({"DAWN, 7", "SAME_DAY, 6", "NEXT_DAY, 14"})
    void 티어별_최대_배송창_길이는_설계서_표와_같다(ServiceTier tier, int hours) {
        assertThat(tier.maxWindowLength()).isEqualTo(Duration.ofHours(hours));
        // 상한 자체는 허용된다.
        assertThat(PromisedWindow.of(START, START.plus(Duration.ofHours(hours)), tier).end())
                .isEqualTo(START.plus(Duration.ofHours(hours)));
    }

    @ParameterizedTest
    @CsvSource({"DAWN, 8", "SAME_DAY, 7", "NEXT_DAY, 15"})
    void 티어_상한을_넘는_배송창은_거부한다(ServiceTier tier, int hours) {
        assertThatThrownBy(() -> PromisedWindow.of(START, START.plus(Duration.ofHours(hours)), tier))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("promisedWindow")
                .hasMessageContaining(tier.name());
    }

    @Test
    void 창_안에_배달되면_정시다() {
        PromisedWindow window = PromisedWindow.of(START, START.plus(Duration.ofHours(7)), ServiceTier.DAWN);

        assertThat(window.isOnTime(START)).isTrue();
        assertThat(window.isOnTime(START.plus(Duration.ofHours(3)))).isTrue();
        assertThat(window.minutesLate(START.plus(Duration.ofHours(3)))).isZero();
    }

    @Test
    void 창_종료_시각은_포함되지_않는다() {
        PromisedWindow window = PromisedWindow.of(START, START.plus(Duration.ofHours(7)), ServiceTier.DAWN);

        // TimeWindow 는 [start, end) 다. 정시율(§8.1) 계산의 경계라 못박는다.
        assertThat(window.isOnTime(window.end())).isFalse();
    }

    @Test
    void 늦으면_지연_분을_돌려준다() {
        PromisedWindow window = PromisedWindow.of(START, START.plus(Duration.ofHours(7)), ServiceTier.DAWN);

        assertThat(window.minutesLate(window.end().plus(Duration.ofMinutes(20)))).isEqualTo(20);
    }

    @Test
    void 시작이_종료보다_뒤면_거부한다() {
        // TimeWindow 가 이미 막는다. PromisedWindow 가 감싸면서 그 검증이 사라지지 않았는지 본다.
        assertThatThrownBy(() -> PromisedWindow.of(START.plusSeconds(1), START, ServiceTier.DAWN))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("시작은 종료보다 앞서야");
    }

    @Test
    void 길이가_0인_창은_거부한다() {
        assertThatThrownBy(() -> PromisedWindow.of(START, START, ServiceTier.DAWN))
                .isInstanceOf(ValidationException.class);
    }
}
