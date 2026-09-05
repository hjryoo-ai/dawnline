package com.dawnline.dispatch.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 라우트 조회 결과 (DESIGN.md §5.3 {@code GET /api/v1/routes/{routeId}}).
 *
 * @param routeId   라우트 id
 * @param planId    계획 id
 * @param vehicleId 차량 id
 * @param driverId  기사 id
 * @param status    라우트 상태
 * @param revision  개정 번호 (§6.8 4단계)
 * @param distanceM 총 이동 거리(m)
 * @param durationS 총 소요 시간(초)
 * @param costKrw   비용
 * @param stops     방문 순서대로의 stop 들
 */
public record RouteView(UUID routeId, UUID planId, UUID vehicleId, @Nullable UUID driverId,
        String status, int revision, int distanceM, int durationS, long costKrw,
        List<StopView> stops) {

    /**
     * stop 하나.
     *
     * @param stopId           stop id
     * @param seq              방문 순번
     * @param lat              위도
     * @param lng              경도
     * @param plannedArrival   계획 도착 시각
     * @param plannedDeparture 계획 출발 시각
     * @param serviceSeconds   하차·전달 시간(초)
     * @param status           {@code PLANNED} 또는 {@code CANCELLED} (§6.10)
     * @param orderIds         이 지점에서 배송할 주문들
     */
    public record StopView(UUID stopId, int seq, double lat, double lng, Instant plannedArrival,
            Instant plannedDeparture, int serviceSeconds, String status, List<UUID> orderIds) {
    }
}
