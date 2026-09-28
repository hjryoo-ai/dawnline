package com.dawnline.dispatch.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.TimeWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 근무는 약속창에 닿는 근무다 (ADR-075 결정 5) — 벽시계 근무창에 날짜를 붙이는 기준이 계획 시각이 아니라 약속창이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JdbcReferenceDataShiftTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);
    private static final LocalTime DAY_START = LocalTime.of(9, 0);
    private static final LocalTime DAY_END = LocalTime.of(22, 0);
    private static final LocalTime NIGHT_START = LocalTime.of(23, 0);
    private static final LocalTime NIGHT_END = LocalTime.of(8, 0);

    private static Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(KST).toInstant();
    }

    @Test
    void 오늘_조기_마감한_내일의_웨이브에는_내일의_주간조가_붙는다() {
        // Compose 스모크의 데모 — 14:03 에 내일의 NEXT_DAY(08:00–22:00)를 닫는다. 처음 판(계획 시각에 아직 끝나지 않은 첫 근무)은
        // 오늘 09–22 를 붙였고, 그 차는 내일 주문을 오늘 배송했다(A34).
        TimeWindow tomorrow = new TimeWindow(at(TODAY.plusDays(1), 8, 0), at(TODAY.plusDays(1), 22, 0));

        TimeWindow shift = JdbcReferenceData.shiftFor(DAY_START, DAY_END, at(TODAY, 14, 3), tomorrow);

        assertThat(shift).isEqualTo(new TimeWindow(at(TODAY.plusDays(1), 9, 0), at(TODAY.plusDays(1), 22, 0)));
    }

    @Test
    void 새벽_창에는_전날_밤에_시작한_야간조가_붙는다() {
        TimeWindow dawn = new TimeWindow(at(TODAY.plusDays(1), 0, 0), at(TODAY.plusDays(1), 7, 0));

        TimeWindow shift = JdbcReferenceData.shiftFor(NIGHT_START, NIGHT_END, at(TODAY, 23, 58), dawn);

        assertThat(shift).isEqualTo(new TimeWindow(at(TODAY, 23, 0), at(TODAY.plusDays(1), 8, 0)));
    }

    @Test
    void 자정_뒤에_도는_새벽_계획에도_어젯밤의_야간조가_붙는다() {
        // 00:30 에 도는 계획 — 오늘 밤(23:00) 조가 아니라 지금 일하고 있는 어젯밤 조다.
        TimeWindow dawn = new TimeWindow(at(TODAY, 0, 0), at(TODAY, 7, 0));

        TimeWindow shift = JdbcReferenceData.shiftFor(NIGHT_START, NIGHT_END, at(TODAY, 0, 30), dawn);

        assertThat(shift).isEqualTo(new TimeWindow(at(TODAY.minusDays(1), 23, 0), at(TODAY, 8, 0)));
    }

    @Test
    void 약속창과_겹치는_근무가_없으면_계획_시각_뒤에_끝나는_첫_근무다_집합의_술어가_뺀다() {
        // 야간조(23–08)와 NEXT_DAY(08–22)는 반열린 구간으로 겹치지 않는다 — 돌려준 근무를 WaveFleet.usable 이 뺀다.
        TimeWindow nextDay = new TimeWindow(at(TODAY.plusDays(1), 8, 0), at(TODAY.plusDays(1), 22, 0));

        TimeWindow shift = JdbcReferenceData.shiftFor(NIGHT_START, NIGHT_END, at(TODAY, 0, 1), nextDay);

        assertThat(shift.end().isAfter(nextDay.start())).as("겹치지 않는다").isFalse();
        assertThat(shift.end()).isAfter(at(TODAY, 0, 1));
    }
}
