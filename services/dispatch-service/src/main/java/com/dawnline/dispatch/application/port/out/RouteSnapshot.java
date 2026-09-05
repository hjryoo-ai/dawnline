package com.dawnline.dispatch.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 개정 발행용 라우트 스냅샷 (DESIGN.md §6.10, ADR-026).
 *
 * <h2>왜 {@code PlannedRoute} 로는 안 되는가</h2>
 * 도메인의 {@code PlannedRoute} 는 <strong>살아 있는</strong> stop 만 안다 — 취소를 모르는 것이
 * 옳다(불변규칙 5, 최적화기는 순수 함수다). 그런데 {@code route.assigned} 는 취소된 stop 을
 * <em>지우지 않고</em> 실어야 하므로(부재는 값이 아니다), 개정 발행의 입력은 계획 결과가 아니라
 * <strong>저장된 라우트 그 자체</strong>여야 한다.
 *
 * @param routeId   라우트 id
 * @param vehicleId 차량 id
 * @param distanceM 총 이동 거리(m). 취소된 stop 은 방문하지 않으므로 빠져 있다
 * @param durationS 총 소요 시간(초)
 * @param costKrw   총 비용(원)
 * @param stops     순번대로의 stop 들. <strong>취소된 것을 포함</strong>한다
 */
public record RouteSnapshot(UUID routeId, UUID vehicleId, int distanceM, int durationS,
        long costKrw, List<StopSnapshot> stops) {

    public RouteSnapshot {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(vehicleId, "vehicleId");
        stops = List.copyOf(Objects.requireNonNull(stops, "stops"));
    }

    /**
     * stop 하나.
     *
     * @param seq               방문 순번. 취소돼도 다시 매기지 않는다 — 기사가 보던 번호다
     * @param orderIds          이 지점의 주문들. 취소된 것도 <strong>남는다</strong>
     * @param cancelledOrderIds 그중 취소된 것들. {@code orderIds} 의 부분집합이다
     * @param lat               위도
     * @param lng               경도
     * @param plannedArrival    계획 도착 시각
     * @param serviceSeconds    하차·전달 시간(초)
     * @param cancelled         stop 자체가 취소됐는가 ({@code route_stops.status})
     */
    public record StopSnapshot(int seq, List<UUID> orderIds, List<UUID> cancelledOrderIds,
            double lat, double lng, Instant plannedArrival, int serviceSeconds,
            boolean cancelled) {

        public StopSnapshot {
            orderIds = List.copyOf(Objects.requireNonNull(orderIds, "orderIds"));
            cancelledOrderIds =
                    List.copyOf(Objects.requireNonNull(cancelledOrderIds, "cancelledOrderIds"));
            Objects.requireNonNull(plannedArrival, "plannedArrival");
            if (!orderIds.containsAll(cancelledOrderIds)) {
                // 소비자가 자기 stop 에 없는 주문의 취소를 듣게 된다.
                throw new IllegalArgumentException(
                        "취소된 주문이 이 stop 의 것이 아닙니다: seq=" + seq);
            }
        }
    }
}
