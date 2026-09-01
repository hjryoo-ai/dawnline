package com.dawnline.common;

import com.dawnline.common.error.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 반열린 시간창 {@code [start, end)}.
 *
 * <p>약속 배송창(DESIGN.md §2.2), 차량 근무창, 웨이브 컷오프 구간에 쓴다.
 * CLAUDE.md 불변규칙 9에 따라 시간은 {@link Instant}({@code TIMESTAMPTZ}) 로만 다룬다.
 *
 * @param start 시작(포함)
 * @param end   종료(제외). {@code start} 보다 뒤여야 한다.
 */
public record TimeWindow(Instant start, Instant end) {

    public TimeWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new ValidationException(
                    "시간창의 시작은 종료보다 앞서야 합니다: " + start + " → " + end,
                    Map.of("start", start.toString(), "end", end.toString()));
        }
    }

    /** 시작 시각과 길이로 만든다. */
    public static TimeWindow of(Instant start, Duration length) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(length, "length");
        return new TimeWindow(start, start.plus(length));
    }

    /** {@code [start, end)} 에 포함되는가. 끝 시각은 포함하지 않는다. */
    public boolean contains(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(start) && instant.isBefore(end);
    }

    /**
     * 다른 시간창과 겹치는가.
     *
     * <p>반열린 구간이므로 한쪽의 끝과 다른 쪽의 시작이 같은 경우(맞닿음)는 겹치지 않는 것으로 본다.
     */
    public boolean overlaps(TimeWindow other) {
        Objects.requireNonNull(other, "other");
        return this.start.isBefore(other.end) && other.start.isBefore(this.end);
    }

    /** 시간창 길이. */
    public Duration duration() {
        return Duration.between(start, end);
    }

    /**
     * 실제 완료 시각이 시간창을 넘긴 분(minute).
     *
     * <p>정시 도착(창 안이거나 창보다 이름)이면 0 을 돌려준다. 넘긴 경우 초 단위는 <strong>버린다</strong>
     * (예: 90초 지연 → 1분). 정시율 KPI 계산에 쓰인다.
     */
    public long minutesLateFor(Instant actual) {
        Objects.requireNonNull(actual, "actual");
        if (!actual.isAfter(end)) {
            return 0L;
        }
        return Duration.between(end, actual).toMinutes();
    }
}
