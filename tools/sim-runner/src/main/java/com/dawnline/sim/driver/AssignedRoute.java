package com.dawnline.sim.driver;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code route.assigned} v1 중 <strong>기사 시뮬레이터가 읽는 필드만</strong>
 * ({@code contracts/events/route.assigned.v1.schema.json}).
 *
 * <p>tracking 의 {@code RouteAssignedPayload} 와 읽는 필드가 다르다. 그것이 소비자 주도 계약의
 * 요점이다 — tracking 은 약속창을 읽고 좌표를 버리지만, 기사는 좌표로 움직이고 약속창은 보지
 * 않는다. {@code planId}·{@code waveId}·{@code vehicleId}·{@code strategy}·{@code costKrw} 는
 * 어느 쪽도 읽지 않아 양쪽 모두에 없다.
 *
 * <p><strong>{@code cancelledOrderIds} 도 읽지 않는다.</strong> 기사에게 중요한 것은 「이 지점에
 * 가야 하는가」 하나이고, 그 답은 stop 의 {@code status} 가 말한다. 주문 일부만 취소된 stop 은
 * 여전히 방문해야 하므로(ADR-026 후속 정정) 부분 취소는 기사의 경로를 바꾸지 않는다.
 *
 * @param routeId  라우트 id
 * @param revision 개정 번호. 최초 확정이 1
 * @param driverId 기사 id. 스캔에는 실리지 않는다 — {@code driver:{id}:pos}(§7.2)의 첫 소비자가
 *                 Phase 6 의 지도이고, 그때 이 값이 필요해진다. 지금은 로그의 라벨이다
 * @param summary  요약. 여기서 읽는 것은 {@code plannedDeparture} 하나다
 * @param stops    방문 순서대로의 stop 들
 */
public record AssignedRoute(UUID routeId, int revision, @Nullable UUID driverId,
        @Nullable Summary summary, List<PlannedStop> stops) {

    /** {@code status} 의 취소 값 (ADR-026). */
    static final String CANCELLED = "CANCELLED";

    public AssignedRoute {
        stops = stops == null ? List.of() : List.copyOf(stops);
    }

    /**
     * 요약 중 읽는 것.
     *
     * @param plannedDeparture 캠프 출발 계획 시각. 이 시뮬레이터의 <strong>시간 원점</strong>이다
     */
    public record Summary(@Nullable Instant plannedDeparture) {
    }

    /**
     * stop 하나.
     *
     * @param seq            방문 순번 (1부터)
     * @param orderIds       이 지점에서 배송할 주문들. 취소된 것도 남아 있다
     * @param lat            위도. 스캔에 그대로 실린다
     * @param lng            경도
     * @param plannedArrival 계획 도착 시각
     * @param serviceSeconds 체류 시간(초). 도착과 완료 사이의 간격이 된다
     * @param status         {@code PLANNED} 또는 {@code CANCELLED}. 부재는 {@code PLANNED} 다
     */
    public record PlannedStop(int seq, List<UUID> orderIds, @Nullable Double lat, @Nullable Double lng,
            @Nullable Instant plannedArrival, @Nullable Integer serviceSeconds, @Nullable String status) {

        public PlannedStop {
            orderIds = orderIds == null ? List.of() : List.copyOf(orderIds);
        }

        /** 이 개정에서 통째로 취소된 stop 인가. */
        public boolean isCancelled() {
            return CANCELLED.equals(status);
        }
    }

    /**
     * 시뮬레이터가 요구하는 필드가 다 있는지 본다.
     *
     * <p>tracking 의 {@code RouteAssignedPayload.toAssignment()} 와 같은 자리, 같은 이유다 —
     * 없는 시각을 지어내면 「주입한 지연이 곧 편차다」라는 전제가 조용히 무너지고, 그 거짓은
     * 시나리오 결과 어디에서도 드러나지 않는다. 계획 시각이 없는 라우트는 <em>돌지 않는다</em>.
     *
     * @throws IllegalStateException 필수 필드가 없으면
     */
    public void requireDrivable() {
        if (summary == null || summary.plannedDeparture() == null) {
            throw new IllegalStateException("""
                    계획 출발 시각 없는 라우트는 돌 수 없습니다: routeId=%s. \
                    route.assigned.v1 의 summary.plannedDeparture 는 required 이므로(Phase 5-1b 계약), \
                    이 이벤트는 그 필드가 생기기 전에 발행된 것이다. \
                    토픽을 재생성하거나 컨슈머 그룹을 latest 로 옮긴다 \
                    (contracts/events/README.md §5)."""
                    .formatted(routeId));
        }
        if (stops.isEmpty()) {
            throw new IllegalStateException("stop 이 없는 라우트는 돌 수 없습니다: routeId=" + routeId);
        }
        for (PlannedStop stop : stops) {
            if (stop.plannedArrival() == null || stop.serviceSeconds() == null) {
                throw new IllegalStateException("""
                        계획 도착 시각 또는 체류 시간이 없는 stop 은 돌 수 없습니다: routeId=%s seq=%d. \
                        route.assigned.v1 의 plannedArrival·serviceSeconds 는 둘 다 required 다."""
                        .formatted(routeId, stop.seq()));
            }
        }
    }

    /** 계획 출발 시각. {@link #requireDrivable()} 를 통과한 뒤에만 부른다. */
    Instant plannedDeparture() {
        Summary found = summary;
        if (found == null || found.plannedDeparture() == null) {
            throw new IllegalStateException("requireDrivable() 를 먼저 부른다");
        }
        return found.plannedDeparture();
    }
}
