package com.dawnline.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.ValidationException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TimeWindow — 반열린 시간창 [start, end)")
class TimeWindowTest {

    private static final Instant START = Instant.parse("2026-08-29T02:00:00Z");
    private static final Instant END = Instant.parse("2026-08-29T06:00:00Z");
    private static final TimeWindow DAWN = new TimeWindow(START, END);

    @Test
    void 생성_시작이_종료보다_앞서면_만들어진다() {
        assertThat(DAWN.start()).isEqualTo(START);
        assertThat(DAWN.end()).isEqualTo(END);
        assertThat(DAWN.duration()).isEqualTo(Duration.ofHours(4));
    }

    @Test
    void of_는_시작_시각과_길이로_만든다() {
        assertThat(TimeWindow.of(START, Duration.ofHours(4))).isEqualTo(DAWN);
        assertThatThrownBy(() -> TimeWindow.of(null, Duration.ofHours(1)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("start");
        assertThatThrownBy(() -> TimeWindow.of(START, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("length");
    }

    @Test
    void 생성_시작이_종료보다_같거나_뒤면_거부한다() {
        assertThatThrownBy(() -> new TimeWindow(START, START))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("앞서야");
        assertThatThrownBy(() -> new TimeWindow(END, START))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TimeWindow.of(START, Duration.ZERO))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 생성_null_은_거부한다() {
        assertThatThrownBy(() -> new TimeWindow(null, END))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("start");
        assertThatThrownBy(() -> new TimeWindow(START, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("end");
    }

    @Test
    void contains_는_시작을_포함하고_종료를_제외한다() {
        assertThat(DAWN.contains(START)).isTrue();
        assertThat(DAWN.contains(START.plusSeconds(1))).isTrue();
        assertThat(DAWN.contains(END.minusMillis(1))).isTrue();
        assertThat(DAWN.contains(END)).isFalse();
        assertThat(DAWN.contains(START.minusMillis(1))).isFalse();
        assertThatThrownBy(() -> DAWN.contains(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("instant");
    }

    @Test
    void overlaps_는_맞닿기만_한_창을_겹친다고_보지_않는다() {
        TimeWindow touchingBefore = new TimeWindow(START.minusSeconds(3600), START);
        TimeWindow touchingAfter = new TimeWindow(END, END.plusSeconds(3600));

        assertThat(DAWN.overlaps(touchingBefore)).isFalse();
        assertThat(DAWN.overlaps(touchingAfter)).isFalse();
        assertThat(touchingBefore.overlaps(DAWN)).isFalse();
    }

    @Test
    void overlaps_는_부분_겹침과_포함을_모두_참으로_본다() {
        TimeWindow partial = new TimeWindow(START.plusSeconds(3600), END.plusSeconds(3600));
        TimeWindow inside = new TimeWindow(START.plusSeconds(60), START.plusSeconds(120));
        TimeWindow outside = new TimeWindow(START.minusSeconds(60), END.plusSeconds(60));
        TimeWindow disjoint = new TimeWindow(END.plusSeconds(1), END.plusSeconds(2));

        assertThat(DAWN.overlaps(partial)).isTrue();
        assertThat(partial.overlaps(DAWN)).isTrue();
        assertThat(DAWN.overlaps(inside)).isTrue();
        assertThat(inside.overlaps(DAWN)).isTrue();
        assertThat(DAWN.overlaps(outside)).isTrue();
        assertThat(DAWN.overlaps(disjoint)).isFalse();
        assertThat(DAWN.overlaps(DAWN)).isTrue();
        assertThatThrownBy(() -> DAWN.overlaps(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("other");
    }

    @Test
    void minutesLateFor_는_창_안이거나_이르면_0_이다() {
        assertThat(DAWN.minutesLateFor(START)).isZero();
        assertThat(DAWN.minutesLateFor(START.minusSeconds(600))).isZero();
        assertThat(DAWN.minutesLateFor(END)).isZero();
    }

    @Test
    void minutesLateFor_는_넘긴_분을_초_단위_버림으로_돌려준다() {
        assertThat(DAWN.minutesLateFor(END.plusSeconds(59))).isZero();
        assertThat(DAWN.minutesLateFor(END.plusSeconds(60))).isEqualTo(1L);
        assertThat(DAWN.minutesLateFor(END.plusSeconds(90))).isEqualTo(1L);
        assertThat(DAWN.minutesLateFor(END.plusSeconds(7_200))).isEqualTo(120L);
        assertThatThrownBy(() -> DAWN.minutesLateFor(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("actual");
    }
}
