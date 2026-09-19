package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.Money;
import com.dawnline.common.error.ValidationException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 확정된 라우트 하나 (DESIGN.md §6.2). {@code route.assigned} 한 건이 된다.
 *
 * @param vehicle   차량
 * @param stops     방문 순서대로 정렬된 stop 들. {@code seq} 는 1부터 연속이어야 한다
 * @param departAt  캠프 출발 계획 시각. 근무창 시작과 계획 시작 중 <em>늦은</em> 쪽이다
 *                  (§6.3 · [ADR-030](docs/adr/ADR-030-night-shift-seed.md)) — 첫 stop 의
 *                  도착에서 이동 시간을 빼서 되돌릴 수 있는 값이 아니다. tracking 이
 *                  {@code DEPARTED_CAMP} 편차의 기준으로 쓴다 (§5.4, Phase 5-1b)
 * @param distanceM 총 이동 거리(m). <strong>캠프 출발·복귀 포함</strong>
 * @param durationS 총 소요 시간(초). 이동 + 서비스
 * @param cost      이 라우트의 비용
 */
public record PlannedRoute(VehicleId vehicle, List<PlannedStop> stops, Instant departAt,
        int distanceM, int durationS, Money cost) {

    public PlannedRoute {
        Objects.requireNonNull(vehicle, "vehicle");
        Objects.requireNonNull(departAt, "departAt");
        Objects.requireNonNull(cost, "cost");
        stops = List.copyOf(Objects.requireNonNull(stops, "stops"));
        if (stops.isEmpty()) {
            throw new ValidationException("빈 라우트는 만들지 않습니다", java.util.Map.of());
        }
        for (int i = 0; i < stops.size(); i++) {
            if (stops.get(i).seq() != i + 1) {
                throw ValidationException.field("stops[" + i + "].seq", stops.get(i).seq(),
                        "방문 순번은 1부터 연속이어야 합니다");
            }
        }
        if (distanceM < 0) {
            throw ValidationException.field("distanceM", distanceM, "거리는 음수일 수 없습니다");
        }
        if (durationS < 0) {
            throw ValidationException.field("durationS", durationS, "시간은 음수일 수 없습니다");
        }
    }

    /** 이 라우트가 배송하는 주문 수. stop 수가 아니다 — 한 stop 에 여러 주문이 있다. */
    public int orderCount() {
        return stops.stream().mapToInt(planned -> planned.stop().orderCount()).sum();
    }

    /** 약속창을 넘긴 stop 수 (§6.9 지표). */
    public long lateStopCount() {
        return stops.stream().filter(planned -> planned.lateMinutes() > 0L).count();
    }
}
