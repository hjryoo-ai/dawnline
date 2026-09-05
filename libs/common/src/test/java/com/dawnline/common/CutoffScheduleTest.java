package com.dawnline.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 컷오프 표 (DESIGN.md §2.2, ADR-020 후속 정정 2).
 *
 * <p>order-service 의 계산과 <em>같은 답</em>인지는 그쪽의 {@code CutoffScheduleContractTest} 가
 * 본다. 여기서는 이 클래스 자체의 규칙을 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CutoffScheduleTest {

    private final CutoffSchedule schedule = CutoffSchedule.standard();

    /** 2026-09-06 09:00 KST. */
    private static final Instant MORNING = Instant.parse("2026-09-06T00:00:00Z");

    @Test
    void SAME_DAY_는_하루에_두_번이다() {
        // 09:00 KST → 오늘 10:00, 11:00 KST → 오늘 14:00, 15:00 KST → 내일 10:00
        assertThat(schedule.cutoffFor("SAME_DAY", MORNING)).isEqualTo(Instant.parse("2026-09-06T01:00:00Z"));
        assertThat(schedule.cutoffFor("SAME_DAY", Instant.parse("2026-09-06T02:00:00Z")))
                .isEqualTo(Instant.parse("2026-09-06T05:00:00Z"));
        assertThat(schedule.cutoffFor("SAME_DAY", Instant.parse("2026-09-06T06:00:00Z")))
                .isEqualTo(Instant.parse("2026-09-07T01:00:00Z"));
    }

    @Test
    void 경계는_포함하지_않는다() {
        // 정확히 10:00 에 접수한 주문은 10:00 컷오프가 아니라 14:00 컷오프에 실린다.
        // "이 시각까지 받는다" 이므로 그 시각에 도착한 것은 이미 늦은 것이다.
        Instant tenAm = Instant.parse("2026-09-06T01:00:00Z");

        assertThat(schedule.cutoffFor("SAME_DAY", tenAm)).isEqualTo(Instant.parse("2026-09-06T05:00:00Z"));
    }

    @Test
    void DAWN_과_NEXT_DAY_는_그날이_끝나는_자정이다() {
        Instant expected = Instant.parse("2026-09-06T15:00:00Z");  // 2026-09-07 00:00 KST

        assertThat(schedule.cutoffFor("DAWN", MORNING)).isEqualTo(expected);
        assertThat(schedule.cutoffFor("NEXT_DAY", MORNING)).isEqualTo(expected);
    }

    @Test
    void 다음_컷오프는_현재보다_반드시_뒤다() {
        // 개정 경로가 쓴다. 같거나 앞이면 무한 루프이거나 이미 마감된 웨이브다.
        for (String tier : new String[] {"DAWN", "SAME_DAY", "NEXT_DAY"}) {
            Instant cutoff = schedule.cutoffFor(tier, MORNING);
            Instant next = schedule.nextCutoffAfter(tier, cutoff);

            assertThat(next).as(tier).isAfter(cutoff);
        }
    }

    @Test
    void 두_번_밀린_주문은_두_번_부르면_된다() {
        Instant first = schedule.cutoffFor("SAME_DAY", MORNING);
        Instant second = schedule.nextCutoffAfter("SAME_DAY", first);
        Instant third = schedule.nextCutoffAfter("SAME_DAY", second);

        assertThat(second).isAfter(first);
        assertThat(third).isAfter(second);
    }

    @Test
    void 시간대는_주입한다() {
        // 컷오프는 벽시계 시각이다. 시간대를 바꾸면 절대 시각이 달라져야 한다.
        CutoffSchedule utc = new CutoffSchedule(ZoneId.of("UTC"));

        assertThat(utc.cutoffFor("SAME_DAY", MORNING))
                .isNotEqualTo(schedule.cutoffFor("SAME_DAY", MORNING));
    }

    @Test
    void 계약에_없는_티어는_거절한다() {
        assertThat(schedule.knows("SAME_DAY")).isTrue();
        assertThat(schedule.knows("EXPRESS")).isFalse();
        assertThatThrownBy(() -> schedule.cutoffFor("EXPRESS", MORNING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("§2.2");
    }
}
