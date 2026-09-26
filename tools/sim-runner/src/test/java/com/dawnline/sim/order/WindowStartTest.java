package com.dawnline.sim.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 창의 시작까지 유효 시각으로 기다린다 (부록 A 「창은 하나다」). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WindowStartTest {

    private static final LocalTime WINDOW = LocalTime.of(22, 58);

    private final List<Long> slept = new ArrayList<>();

    private WindowStart at(String instant) {
        return new WindowStart(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), slept::add);
    }

    @Test
    void 창이_앞에_있으면_KST_로_그만큼_기다린다() throws InterruptedException {
        // 13:40Z = 22:40 KST — make sim-up 의 기본 유효 시각. 오프셋은 시계가 이미 품고 있다.
        at("2026-09-27T13:40:00Z").await(WINDOW);

        assertThat(slept).containsExactly(Duration.ofMinutes(18).toNanos());
    }

    @Test
    void 바로_그_시각이면_기다리지_않는다() throws InterruptedException {
        at("2026-09-27T13:58:00Z").await(WINDOW);

        assertThat(slept).containsExactly(0L);
    }

    @Test
    void 날짜를_넘는_창도_다음_것을_고른다() throws InterruptedException {
        // 23:30 KST 에 00:10 창 — 40분 뒤, 다음 날이다.
        at("2026-09-27T14:30:00Z").await(LocalTime.of(0, 10));

        assertThat(slept).containsExactly(Duration.ofMinutes(40).toNanos());
    }

    @Test
    void 창을_지났으면_기다리지_않고_다시_세우라고_말한다() {
        // 23:10 KST — 22:58 창은 12분 전에 열렸다. 다음 것은 23시간 48분 뒤다.
        assertThatThrownBy(() -> at("2026-09-27T14:10:00Z").await(WINDOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("make sim-down && make sim-up")
                .hasMessageContaining("PT23H48M");
        assertThat(slept).isEmpty();
    }
}
