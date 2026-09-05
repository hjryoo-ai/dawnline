package com.dawnline.dispatch.adapter.out.messaging;

import com.dawnline.dispatch.application.port.out.DispatchEvents;
import com.dawnline.dispatch.application.port.out.DriverLookup;
import com.dawnline.dispatch.application.port.out.RouteSnapshot;
import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.messaging.outbox.OutboxAppender;
import com.dawnline.messaging.outbox.OutboxMessage;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link DispatchEvents} 의 outbox 구현 (불변규칙 1, §4.4).
 *
 * <p>{@code append} 는 {@code outbox_events} 에 행을 INSERT 할 뿐이고 그 INSERT 는 호출한
 * 유스케이스의 트랜잭션에 참여한다 — 계획이 롤백되면 세 이벤트도 함께 사라진다. 그것이
 * ADR-024 가 "같은 outbox 트랜잭션" 을 요구한 이유이고, 여기서 저절로 지켜진다.
 *
 * <p>파티션 키는 §4.1 대로다 — {@code route.assigned} 는 {@code routeId},
 * {@code order.dispatched} 는 {@code orderId}, 계획 결과 둘은 {@code waveId}.
 */
public class OutboxDispatchEvents implements DispatchEvents {

    private final OutboxAppender outbox;
    private final DriverLookup drivers;
    private final Clock clock;

    /**
     * @param outbox  이벤트 발행의 유일한 진입점
     * @param drivers 차량 → 기사. {@code route.assigned.driverId} 에 쓴다
     * @param clock   시각 출처 (불변규칙 12)
     */
    public OutboxDispatchEvents(OutboxAppender outbox, DriverLookup drivers, Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.drivers = Objects.requireNonNull(drivers, "drivers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void routeAssigned(RoutePlan plan, UUID routeId, PlannedRoute route, int revision) {
        // 기사가 없는 차량은 계약이 허용하지 않는다(driverId 는 required). 시드가 1:1 이라
        // 없을 수 없지만, 없으면 차량 id 로 대신한다 — 발행을 멈추는 것보다 낫고, 그 사실은
        // 라우트 조회에서 바로 눈에 띈다.
        UUID driverId = drivers.driverOf(route.vehicle().value()).orElse(route.vehicle().value());
        outbox.append(OutboxMessage.of(
                RouteAssignedPayload.AGGREGATE_TYPE,
                routeId,
                RouteAssignedPayload.EVENT_TYPE,
                RouteAssignedPayload.SCHEMA_VERSION,
                routeId.toString(),
                RouteAssignedPayload.of(plan, routeId, driverId, route, revision)));
    }

    @Override
    public void routeRevised(RoutePlan plan, RouteSnapshot snapshot, int revision) {
        UUID driverId = drivers.driverOf(snapshot.vehicleId()).orElse(snapshot.vehicleId());
        outbox.append(OutboxMessage.of(
                RouteAssignedPayload.AGGREGATE_TYPE,
                snapshot.routeId(),
                RouteAssignedPayload.EVENT_TYPE,
                RouteAssignedPayload.SCHEMA_VERSION,
                snapshot.routeId().toString(),
                RouteAssignedPayload.of(plan, snapshot, driverId, revision)));
    }

    @Override
    public void ordersDispatched(UUID routeId, List<UUID> orderIds) {
        Objects.requireNonNull(orderIds, "orderIds");
        for (UUID orderId : orderIds) {
            outbox.append(OutboxMessage.of(
                    OrderDispatchedPayload.AGGREGATE_TYPE,
                    orderId,
                    OrderDispatchedPayload.EVENT_TYPE,
                    OrderDispatchedPayload.SCHEMA_VERSION,
                    orderId.toString(),
                    OrderDispatchedPayload.of(orderId, routeId, clock.instant())));
        }
    }

    @Override
    public void planCompleted(RoutePlan plan, PlanResult result) {
        outbox.append(OutboxMessage.of(
                PlanResultPayloads.AGGREGATE_TYPE,
                plan.id(),
                PlanResultPayloads.COMPLETED_EVENT_TYPE,
                PlanResultPayloads.SCHEMA_VERSION,
                plan.waveId().toString(),
                PlanResultPayloads.completed(plan, result)));
    }

    @Override
    public void planFailed(RoutePlan plan) {
        outbox.append(OutboxMessage.of(
                PlanResultPayloads.AGGREGATE_TYPE,
                plan.id(),
                PlanResultPayloads.FAILED_EVENT_TYPE,
                PlanResultPayloads.SCHEMA_VERSION,
                plan.waveId().toString(),
                PlanResultPayloads.failed(plan)));
    }
}
