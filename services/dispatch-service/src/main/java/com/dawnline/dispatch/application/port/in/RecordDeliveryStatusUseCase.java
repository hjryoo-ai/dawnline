package com.dawnline.dispatch.application.port.in;

import com.dawnline.dispatch.domain.RouteStopStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code delivery.status} 를 {@code route_stops.status} 에 반영한다 (DESIGN.md §4.1, ADR-047).
 */
public interface RecordDeliveryStatusUseCase {

    /**
     * 한 건을 반영한다.
     *
     * @param command 스캔이 말하는 것
     */
    void record(DeliveryStatusCommand command);

    /**
     * 스캔 한 건.
     *
     * @param routeId    라우트 id — <strong>이 라우트에서만</strong> 찾는다. 주문이 다른 라우트로
     *                   옮겨 갔으면 이 이벤트는 철 지난 것이다
     * @param stopSeq    페이로드가 말하는 순번. <strong>조회에 쓰지 않는다</strong> — 개정이
     *                   {@code seq} 의 뜻을 바꾸기 때문이다(ADR-047 결정 1). 주문으로 찾은
     *                   stop 의 순번과 다르면 로그로만 남긴다
     * @param orderIds   이 stop 에 묶인 주문들. <strong>조회 키</strong>다
     * @param status     보고된 상태
     * @param occurredAt 사건 발생 시각
     */
    record DeliveryStatusCommand(UUID routeId, int stopSeq, List<UUID> orderIds,
            RouteStopStatus status, Instant occurredAt) {

        public DeliveryStatusCommand {
            Objects.requireNonNull(routeId, "routeId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(occurredAt, "occurredAt");
            orderIds = List.copyOf(Objects.requireNonNull(orderIds, "orderIds"));
            if (orderIds.isEmpty()) {
                throw new IllegalArgumentException("orderIds 가 비었습니다: routeId=" + routeId);
            }
        }
    }
}
