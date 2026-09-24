package com.dawnline.ops.application;

import com.dawnline.ops.application.port.in.Fact;
import com.dawnline.ops.application.port.in.ProjectFactUseCase;
import com.dawnline.ops.application.port.out.OrderColumn;
import com.dawnline.ops.application.port.out.OrderRows;
import com.dawnline.ops.application.port.out.OrderRows.OrderRow;
import com.dawnline.ops.application.port.out.Patch;
import com.dawnline.ops.application.port.out.RouteColumn;
import com.dawnline.ops.application.port.out.RouteRows;
import com.dawnline.ops.application.port.out.RouteRows.RouteRow;
import com.dawnline.ops.application.port.out.WaveColumn;
import com.dawnline.ops.application.port.out.WaveRows;
import com.dawnline.ops.domain.DeliveryOutcome;
import com.dawnline.ops.domain.OrderStatus;
import com.dawnline.ops.domain.Progress;
import com.dawnline.ops.domain.RouteStatus;
import com.dawnline.ops.domain.Verdict;
import com.dawnline.ops.domain.WaveStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 사실을 읽기 모델에 반영한다 — 먼저 온 사실로 행을 만들고 늦게 온 사실로 채운다
 * (DESIGN.md §5.5, ADR-051).
 *
 * <h2>이 클래스에 없는 문장</h2>
 * 「이 사실은 저 사실 뒤에 온다」는 문장이 한 줄도 없다(ADR-051 결과). 모든 경로가 같은 모양이다 —
 * 행을 잠그고(없으면 키만으로 만들고), 축·개정 칸은 {@link Progress} 로 판정하고, 나머지는 자기
 * 칸만 적는다. 개수 칸은 적지 않고 다시 센다.
 *
 * <h2>잠금 순서: 주문 → 라우트 → 웨이브</h2>
 * 서로 다른 파티션의 사실이 같은 행을 동시에 쓰므로 판정과 쓰기 사이에 행 잠금이 필요하고, 여러
 * 행을 잠그는 트랜잭션끼리 순서가 다르면 교착한다. 그래서 순서를 하나로 정했다: 표는
 * {@code rm_orders} → {@code rm_routes} → {@code rm_waves}, 표 안에서는 키 순서(어댑터가 지킨다).
 * 한 사실은 표마다 <strong>한 번에</strong> 잠근다 — 두 번에 나누면 두 번째 묶음이 첫 묶음보다 작은
 * 키를 가질 수 있다. 라우트 묶음을 주문 뒤에 정하는 이유가 그것이다(주문의 지금 라우트를 알아야
 * 어느 라우트를 다시 셀지 안다).
 *
 * <h2>상태 칸만 판정한다 — 데이터 칸은 판정과 무관하게 적는다</h2>
 * {@code order.placed} 가 {@code DISPATCHED} 뒤에 와도 고객·티어·원 약속은 적는다. 축이 거르는 것은
 * 「어느 상태인가」 하나이고, 그 이벤트가 싣고 온 다른 사실은 순서와 무관하게 참이다 — ADR-017 의
 * 경고(「STALE 이어도 데이터는 적용」)와 같은 자리다. 개정·시각으로 거르는 칸(라우트의 계획,
 * 주문의 계획·ETA)은 다르다: 그 칸들은 <em>나중의 사실이 앞의 사실을 대체</em>하는 칸이다.
 */
public class ReadModelProjector implements ProjectFactUseCase {

    private final OrderRows orders;
    private final RouteRows routes;
    private final WaveRows waves;
    private final Clock clock;

    /**
     * @param orders {@code rm_orders}
     * @param routes {@code rm_routes}
     * @param waves  {@code rm_waves}
     * @param clock  {@code updated_at} 시각 출처 (불변규칙 12)
     */
    public ReadModelProjector(OrderRows orders, RouteRows routes, WaveRows waves, Clock clock) {
        this.orders = Objects.requireNonNull(orders, "orders");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.waves = Objects.requireNonNull(waves, "waves");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Projection project(Fact fact) {
        Objects.requireNonNull(fact, "fact");
        int stale = switch (fact) {
            case Fact.OrderPlaced f -> orderPlaced(f);
            case Fact.FulfillmentPlanned f -> fulfillmentPlanned(f);
            case Fact.OrderDispatched f -> orderStatusOnly(f.orderId(), OrderStatus.DISPATCHED);
            case Fact.OrderCancelled f -> orderStatusOnly(f.orderId(), OrderStatus.CANCELLED);
            case Fact.WaveClosed f -> waveClosed(f);
            case Fact.RouteAssigned f -> routeAssigned(f);
            case Fact.PlanCompleted f -> planCompleted(f);
            case Fact.PlanFailed f -> planFailed(f);
            case Fact.DeliveryStatus f -> deliveryStatus(f);
            case Fact.DeliveryAtRisk f -> deliveryAtRisk(f);
            case Fact.RouteDeparted f -> routeDeparted(f);
        };
        return stale == 0 ? Projection.CLEAN : new Projection(stale);
    }

    // --- 주문 쪽 축 ------------------------------------------------------------

    private int orderPlaced(Fact.OrderPlaced f) {
        OrderRow row = lockOne(f.orderId());
        Patch<OrderColumn> patch = Patch.of(OrderColumn.class)
                .set(OrderColumn.CUSTOMER_ID, f.customerId())
                .set(OrderColumn.SERVICE_TIER, f.serviceTier())
                .set(OrderColumn.PROMISED_END_ORIGINAL, f.promisedEnd())
                .set(OrderColumn.PLACED_AT, f.placedAt());
        Verdict verdict = Progress.judge(row.orderStatus(), OrderStatus.PLACED);
        if (verdict.writes()) {
            patch.set(OrderColumn.ORDER_STATUS, OrderStatus.PLACED.name());
        }
        orders.write(f.orderId(), patch, clock.instant());
        return staleOf(verdict);
    }

    private int fulfillmentPlanned(Fact.FulfillmentPlanned f) {
        OrderRow row = lockOne(f.orderId());
        OrderStatus target = f.serviceable() ? OrderStatus.PLANNED : OrderStatus.UNSERVICEABLE;
        Patch<OrderColumn> patch = Patch.of(OrderColumn.class);
        UUID waveId = f.waveId();
        if (f.serviceable()) {
            // 배차되지 못한 주문에는 캠프·웨이브도, 개정할 약속도 없다(§4.3) — 그 칸은 쓰지 않는다.
            patch.set(OrderColumn.CAMP_ID, Objects.requireNonNull(f.campId(), "campId"))
                    .set(OrderColumn.WAVE_ID, Objects.requireNonNull(waveId, "waveId"))
                    .set(OrderColumn.PROMISED_END_REVISED, f.promisedEnd());
        }
        Verdict verdict = Progress.judge(row.orderStatus(), target);
        if (verdict.writes()) {
            patch.set(OrderColumn.ORDER_STATUS, target.name());
        }
        orders.write(f.orderId(), patch, clock.instant());
        int stale = staleOf(verdict);

        if (f.serviceable() && waveId != null) {
            stale += waveFact(waveId, f.campId(), f.serviceTier(), f.waveCutoffAt(), WaveStatus.OPEN,
                    Patch.of(WaveColumn.class));
            // 편입을 바꾸는 사실은 이것 하나다 — 그래서 웨이브의 개수는 여기서만 다시 센다.
            waves.recountOrders(waveId);
        }
        return stale;
    }

    private int orderStatusOnly(UUID orderId, OrderStatus target) {
        OrderRow row = lockOne(orderId);
        Verdict verdict = Progress.judge(row.orderStatus(), target);
        if (verdict.writes()) {
            orders.write(orderId, Patch.of(OrderColumn.class).set(OrderColumn.ORDER_STATUS, target.name()),
                    clock.instant());
        }
        return staleOf(verdict);
    }

    // --- 웨이브 ----------------------------------------------------------------

    private int waveClosed(Fact.WaveClosed f) {
        return waveFact(f.waveId(), f.campId(), f.serviceTier(), f.cutoffAt(), WaveStatus.CLOSED,
                Patch.of(WaveColumn.class));
    }

    private int planCompleted(Fact.PlanCompleted f) {
        return waveFact(f.waveId(), f.campId(), null, null, WaveStatus.PLANNED, Patch.of(WaveColumn.class)
                .set(WaveColumn.PLAN_ID, f.planId())
                .set(WaveColumn.ROUTE_COUNT, f.routeCount())
                .set(WaveColumn.UNASSIGNED_COUNT, f.unassignedCount())
                .set(WaveColumn.TOTAL_COST_KRW, f.totalCostKrw())
                .set(WaveColumn.PLAN_DURATION_MS, f.planDurationMs()));
    }

    private int planFailed(Fact.PlanFailed f) {
        return waveFact(f.waveId(), f.campId(), null, null, WaveStatus.PLAN_FAILED, Patch.of(WaveColumn.class));
    }

    /**
     * 웨이브에 쓰는 네 사실의 공통 모양 — 키 속성은 비어 있을 때만, 상태는 축으로, 나머지는 그대로.
     */
    private int waveFact(UUID waveId, @Nullable UUID campId, @Nullable String serviceTier,
            @Nullable Instant cutoffAt, WaveStatus target, Patch<WaveColumn> patch) {
        WaveRows.WaveRow row = waves.lock(waveId);
        if (campId != null) {
            patch.setIfAbsent(WaveColumn.CAMP_ID, campId);
        }
        if (serviceTier != null) {
            patch.setIfAbsent(WaveColumn.SERVICE_TIER, serviceTier);
        }
        if (cutoffAt != null) {
            patch.setIfAbsent(WaveColumn.CUTOFF_AT, cutoffAt);
        }
        Verdict verdict = Progress.judge(row.status(), target);
        if (verdict.writes()) {
            patch.set(WaveColumn.STATUS, target.name());
        }
        waves.write(waveId, patch);
        return staleOf(verdict);
    }

    // --- 라우트 ----------------------------------------------------------------

    private int routeAssigned(Fact.RouteAssigned f) {
        // 1) 주문 — 계획 칸은 라우트를 넘어 발행 시각으로 견준다(§5.5 「DDL 정정」).
        Map<UUID, Instant> arrivals = new HashMap<>();
        for (Fact.AssignedStop stop : f.stops()) {
            for (UUID orderId : stop.orderIds()) {
                arrivals.put(orderId, stop.plannedArrival());
            }
        }
        Map<UUID, OrderRow> rows = orders.lock(arrivals.keySet());
        int stale = 0;
        Set<UUID> recount = new LinkedHashSet<>();
        recount.add(f.routeId());
        for (Map.Entry<UUID, Instant> arrival : arrivals.entrySet()) {
            OrderRow row = rows.get(arrival.getKey());
            Verdict verdict = Progress.judgeVersion(row.plannedAsOf(), f.asOf());
            if (verdict.writes()) {
                if (row.routeId() != null) {
                    // 이 주문이 떠나온 라우트 — 그 라우트의 개수도 바뀐다.
                    recount.add(row.routeId());
                }
                orders.write(arrival.getKey(), Patch.of(OrderColumn.class)
                        .set(OrderColumn.ROUTE_ID, f.routeId())
                        .set(OrderColumn.PLANNED_ARRIVAL, arrival.getValue())
                        .set(OrderColumn.PLANNED_AS_OF, f.asOf()), clock.instant());
            }
            stale += staleOf(verdict);
        }

        // 2) 라우트 — 라우트 칸은 개정 번호로 거른다(§6.8 4단계, ADR-045).
        Map<UUID, RouteRow> routeRows = routes.lock(recount);
        RouteRow route = routeRows.get(f.routeId());
        Patch<RouteColumn> patch = Patch.of(RouteColumn.class).setIfAbsent(RouteColumn.CAMP_ID, f.campId());
        Verdict revision = Progress.judgeVersion(route.revision(), f.revision());
        if (revision.writes()) {
            patch.set(RouteColumn.REVISION, f.revision())
                    .set(RouteColumn.PLAN_ID, f.planId())
                    .set(RouteColumn.VEHICLE_ID, f.vehicleId())
                    .set(RouteColumn.DRIVER_ID, f.driverId())
                    .set(RouteColumn.STOP_COUNT, f.stopCount())
                    .set(RouteColumn.DISTANCE_M, f.distanceM())
                    .set(RouteColumn.COST_KRW, f.costKrw())
                    .set(RouteColumn.PLANNED_DEPARTURE, f.plannedDeparture());
        }
        if (Progress.judge(route.status(), RouteStatus.ASSIGNED).writes()) {
            // 이 축에서 ASSIGNED 는 맨 아래라 역행이 정상이다(출발이 먼저 왔을 뿐) — 세지 않는다.
            patch.set(RouteColumn.STATUS, RouteStatus.ASSIGNED.name());
        }
        routes.write(f.routeId(), patch);
        routes.recount(recount);
        return stale + staleOf(revision);
    }

    private int deliveryStatus(Fact.DeliveryStatus f) {
        int stale = 0;
        Set<UUID> routeIds = new LinkedHashSet<>();
        routeIds.add(f.routeId());
        DeliveryOutcome outcome = f.outcome();
        if (outcome != null) {
            Map<UUID, OrderRow> rows = orders.lock(f.orderIds());
            for (UUID orderId : f.orderIds()) {
                OrderRow row = rows.get(orderId);
                Verdict verdict = Progress.judge(row.deliveryOutcome(), outcome);
                if (verdict.writes()) {
                    Patch<OrderColumn> patch = Patch.of(OrderColumn.class)
                            .set(OrderColumn.DELIVERY_OUTCOME, outcome.name());
                    // 결과의 시각은 결과와 같은 패치에서만 — 축이 옮길 때만 쓴다. 두 칸은 배타다
                    // (ck_rmo_outcome_time_exclusive): 배송 축 KPI 가 COALESCE 로 버킷을 잡는다(§5.5).
                    patch.set(outcome == DeliveryOutcome.COMPLETED ? OrderColumn.DELIVERED_AT : OrderColumn.FAILED_AT,
                            f.occurredAt());
                    orders.write(orderId, patch, clock.instant());
                }
                if (row.routeId() != null) {
                    // 사실은 주문으로 식별한다(ADR-047) — 다시 세는 것은 이벤트가 말한 라우트가
                    // 아니라 그 주문이 <em>지금 계획상</em> 있는 라우트다.
                    routeIds.add(row.routeId());
                }
                stale += staleOf(verdict);
            }
        }

        Map<UUID, RouteRow> routeRows = routes.lock(routeIds);
        // 배송이 일어났다면 라우트는 출발한 것이다 — ARRIVED 도 같다. 역행은 없다(DEPARTED 가 맨 위).
        if (Progress.judge(routeRows.get(f.routeId()).status(), RouteStatus.DEPARTED).writes()) {
            routes.write(f.routeId(), Patch.of(RouteColumn.class)
                    .set(RouteColumn.STATUS, RouteStatus.DEPARTED.name()));
        }
        if (outcome != null) {
            routes.recount(routeIds);
        }
        return stale;
    }

    private int deliveryAtRisk(Fact.DeliveryAtRisk f) {
        List<UUID> orderIds = new ArrayList<>(f.etas().size());
        for (Fact.OrderEta eta : f.etas()) {
            orderIds.add(eta.orderId());
        }
        Map<UUID, OrderRow> rows = orders.lock(orderIds);
        int stale = 0;
        for (Fact.OrderEta eta : f.etas()) {
            Verdict verdict = Progress.judgeVersion(rows.get(eta.orderId()).etaAsOf(), f.detectedAt());
            if (verdict.writes()) {
                orders.write(eta.orderId(), Patch.of(OrderColumn.class)
                        .set(OrderColumn.ETA_AT, eta.etaAt())
                        .set(OrderColumn.ETA_AS_OF, f.detectedAt()), clock.instant());
            }
            stale += staleOf(verdict);
        }
        routes.lock(List.of(f.routeId()));
        // at-risk 는 사건이고(ADR-046) 해제가 오지 않는다 — 이 칸은 「통지가 있었다」를 적는다.
        routes.write(f.routeId(), Patch.of(RouteColumn.class)
                .setIfAbsent(RouteColumn.CAMP_ID, f.campId())
                .set(RouteColumn.AT_RISK, Boolean.TRUE));
        return stale;
    }

    private int routeDeparted(Fact.RouteDeparted f) {
        RouteRow row = routes.lock(List.of(f.routeId())).get(f.routeId());
        Patch<RouteColumn> patch = Patch.of(RouteColumn.class)
                .setIfAbsent(RouteColumn.CAMP_ID, f.campId())
                .set(RouteColumn.DEPARTED_AT, f.departedAt());
        Verdict verdict = Progress.judge(row.status(), RouteStatus.DEPARTED);
        if (verdict.writes()) {
            patch.set(RouteColumn.STATUS, RouteStatus.DEPARTED.name());
        }
        routes.write(f.routeId(), patch);
        return staleOf(verdict);
    }

    // --- 공통 ------------------------------------------------------------------

    private OrderRow lockOne(UUID orderId) {
        return orders.lock(List.of(orderId)).get(orderId);
    }

    private static int staleOf(Verdict verdict) {
        return verdict == Verdict.STALE ? 1 : 0;
    }
}
