package com.dawnline.dispatch.adapter.out.messaging;

import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.PlannedStop;
import java.util.List;
import java.util.UUID;

/**
 * {@code route.assigned.v1} 페이로드 (계약: {@code contracts/events/route.assigned.v1.schema.json}).
 *
 * <p>{@code stops} 가 통째로 들어간다 — tracking 은 이 이벤트만으로 stop 별 shipment 를 만들 수
 * 있어야 한다(불변규칙 4).
 *
 * @param routeId  라우트 id (파티션 키와 같아야 한다)
 * @param planId   계획 id
 * @param waveId   웨이브 id
 * @param campId   캠프 id
 * @param vehicleId 차량 id
 * @param driverId 기사 id
 * @param strategy 전략 이름
 * @param revision 개정 번호. 최초 확정이 1 (§6.8 4단계)
 * @param summary  요약
 * @param stops    방문 순서대로의 stop 들
 */
public record RouteAssignedPayload(UUID routeId, UUID planId, UUID waveId, UUID campId,
        UUID vehicleId, UUID driverId, String strategy, int revision, Summary summary,
        List<StopPayload> stops) {

    /** {@code eventType}. */
    public static final String EVENT_TYPE = "route.assigned";

    /** 페이로드 스키마 major. */
    public static final int SCHEMA_VERSION = 1;

    /** {@code outbox_events.aggregate_type}. */
    public static final String AGGREGATE_TYPE = "Route";

    /**
     * 요약.
     *
     * @param stopCount  stop 수. {@code stops} 길이와 같아야 한다 (계약 테스트가 검사한다)
     * @param distanceM  총 이동 거리(m)
     * @param durationS  총 소요 시간(초)
     * @param costKrw    총 비용(원)
     */
    public record Summary(int stopCount, int distanceM, int durationS, long costKrw) {
    }

    /**
     * stop 하나.
     *
     * @param seq             방문 순번 (1부터 연속)
     * @param orderIds        이 지점에서 배송할 주문들
     * @param lat             위도
     * @param lng             경도
     * @param plannedArrival  계획 도착 시각
     * @param serviceSeconds  하차·전달 시간(초)
     * @param status          {@code PLANNED} 또는 {@code CANCELLED} (ADR-026)
     */
    public record StopPayload(int seq, List<String> orderIds, double lat, double lng,
            String plannedArrival, int serviceSeconds, String status) {
    }

    /**
     * 라우트에서 만든다.
     *
     * @param plan      계획
     * @param routeId   라우트 id
     * @param driverId  기사 id
     * @param route     라우트
     * @param revision  개정 번호
     */
    public static RouteAssignedPayload of(RoutePlan plan, UUID routeId, UUID driverId,
            PlannedRoute route, int revision) {

        List<StopPayload> stops = route.stops().stream().map(RouteAssignedPayload::stopOf).toList();
        return new RouteAssignedPayload(routeId, plan.id(), plan.waveId(), plan.campId(),
                route.vehicle().value(), driverId,
                plan.strategy().orElseThrow(() -> new IllegalStateException("전략 없이 발행할 수 없습니다")),
                revision,
                new Summary(stops.size(), route.distanceM(), route.durationS(), route.cost().krw()),
                stops);
    }

    private static StopPayload stopOf(PlannedStop planned) {
        return new StopPayload(planned.seq(),
                planned.stop().orderIds().stream().map(id -> id.value().toString()).toList(),
                planned.stop().point().lat(), planned.stop().point().lng(),
                planned.arrival().toString(), planned.stop().serviceSeconds(),
                // 발행 시점의 stop 은 모두 살아 있다 — 취소된 것은 PlanPruner 가 이미 뺐다.
                // CANCELLED 는 §6.10 의 개정 발행에서 쓰인다.
                "PLANNED");
    }
}
