package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.error.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 순서와 시각이 정해진 stop (DESIGN.md §6.2, `route_stops` 한 행).
 *
 * @param seq       방문 순번 (1부터 연속)
 * @param stop      통합된 방문 지점
 * @param arrival   계획 도착 시각. {@code route.assigned} 의 {@code plannedArrival} 이 된다
 * @param departure 계획 출발 시각 ({@code arrival + serviceSeconds})
 */
public record PlannedStop(int seq, Stop stop, Instant arrival, Instant departure) {

    public PlannedStop {
        Objects.requireNonNull(stop, "stop");
        Objects.requireNonNull(arrival, "arrival");
        Objects.requireNonNull(departure, "departure");
        if (seq < 1) {
            throw ValidationException.field("seq", seq, "방문 순번은 1 이상이어야 합니다");
        }
        if (departure.isBefore(arrival)) {
            throw ValidationException.field("departure", departure, "출발은 도착보다 앞설 수 없습니다");
        }
    }

    /**
     * 약속창을 넘긴 분(minute). 넘기지 않았으면 0 이다.
     *
     * <p>기준은 <strong>도착</strong> 시각이다 — 고객이 겪는 것은 물건이 도착한 시각이지 기사가
     * 떠난 시각이 아니다. {@code TIME_WINDOW_LIMIT}(하드)과 {@code TIME_WINDOW_PENALTY}(소프트)가
     * 같은 값을 본다.
     */
    public long lateMinutes() {
        if (!arrival.isAfter(stop.promised().end())) {
            return 0L;
        }
        return Duration.between(stop.promised().end(), arrival).toMinutes();
    }
}
