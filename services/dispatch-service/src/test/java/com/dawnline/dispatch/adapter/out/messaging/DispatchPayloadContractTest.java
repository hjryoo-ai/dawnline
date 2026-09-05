package com.dawnline.dispatch.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.Money;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.domain.PlanMode;
import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.PlanMetrics;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.PlannedStop;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleId;
import com.dawnline.messaging.contract.EventContracts;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 발행 페이로드가 계약을 지키는지 (불변규칙 8).
 *
 * <p>브로커까지 가는 것은 {@code PlanExecutionIT} 가 보고, 여기서는 <strong>페이로드 모양</strong>
 * 만 본다 — 컨테이너 없이 도는 검사라 실패가 빠르고 원인이 좁다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DispatchPayloadContractTest {

    private static final EventContracts CONTRACTS = EventContracts.load();
    private static final Instant NOW = Instant.parse("2026-09-06T01:00:00Z");

    private static RoutePlan publishedPlan() {
        RoutePlan plan = RoutePlan.request(Ids.newId(), Ids.newId(), Ids.newId());
        plan.begin("sweep-greedy-nn", PlanMode.FULL, 42L, 1, NOW);
        plan.complete(Money.krw(1_500_000), 3, 1, 674, NOW.plusSeconds(1));
        return plan;
    }

    private static PlannedRoute route() {
        Stop stop = new Stop(GeoPoint.of(37.4979, 127.0276),
                List.of(OrderId.of(Ids.newId()), OrderId.of(Ids.newId())), Parcel.EMPTY,
                new TimeWindow(NOW, NOW.plus(Duration.ofHours(4))), 120, 0);
        return new PlannedRoute(VehicleId.of(Ids.newId()),
                List.of(new PlannedStop(1, stop, NOW.plusSeconds(600), NOW.plusSeconds(720))),
                8_420, 2_340, Money.krw(21_500));
    }

    @Test
    void route_assigned_가_계약을_지킨다() {
        RoutePlan plan = publishedPlan();
        UUID routeId = Ids.newId();

        var payload = RouteAssignedPayload.of(plan, routeId, Ids.newId(), route(), 1);

        CONTRACTS.validatePayload(RouteAssignedPayload.EVENT_TYPE,
                RouteAssignedPayload.SCHEMA_VERSION, CONTRACTS.json().toTree(payload));
    }

    @Test
    void route_assigned_의_stopCount_는_stops_길이와_같다() {
        // 계약이 도메인 불변식으로 적어 둔 것이고, 계약 테스트가 그것을 검사한다.
        var payload = RouteAssignedPayload.of(publishedPlan(), Ids.newId(), Ids.newId(), route(), 1);

        assertThat(payload.summary().stopCount()).isEqualTo(payload.stops().size());
    }

    @Test
    void order_dispatched_가_계약을_지킨다() {
        var payload = OrderDispatchedPayload.of(Ids.newId(), Ids.newId(), NOW);

        CONTRACTS.validatePayload(OrderDispatchedPayload.EVENT_TYPE,
                OrderDispatchedPayload.SCHEMA_VERSION, CONTRACTS.json().toTree(payload));
    }

    @Test
    void plan_completed_가_계약을_지킨다() {
        RoutePlan plan = publishedPlan();
        PlanResult result = new PlanResult(List.of(route()), List.of(), Money.krw(1_500_000),
                new PlanMetrics(1, 2, 0, 1, 8_420, 2_340, 0, 0, 674), List.of());

        CONTRACTS.validatePayload(PlanResultPayloads.COMPLETED_EVENT_TYPE,
                PlanResultPayloads.SCHEMA_VERSION,
                CONTRACTS.json().toTree(PlanResultPayloads.completed(plan, result)));
    }

    @Test
    void plan_failed_가_계약을_지킨다() {
        RoutePlan plan = RoutePlan.request(Ids.newId(), Ids.newId(), Ids.newId());
        plan.begin("sweep-greedy-nn", PlanMode.FULL, 1L, 1, NOW);
        plan.fail("NO_CANDIDATES", NOW.plusSeconds(1));

        CONTRACTS.validatePayload(PlanResultPayloads.FAILED_EVENT_TYPE,
                PlanResultPayloads.SCHEMA_VERSION,
                CONTRACTS.json().toTree(PlanResultPayloads.failed(plan)));
    }

    @Test
    void 넓힌_사유도_계약을_지킨다() {
        // 2026-09-05 에 enum 을 넓혔다 (§4.7 — 같은 major 안에서 값 추가는 허용).
        RoutePlan plan = RoutePlan.request(Ids.newId(), Ids.newId(), Ids.newId());
        plan.begin("sweep-greedy-nn", PlanMode.FULL, 1L, 1, NOW);
        plan.fail("RULE_VIOLATION", NOW.plusSeconds(1));

        CONTRACTS.validatePayload(PlanResultPayloads.FAILED_EVENT_TYPE,
                PlanResultPayloads.SCHEMA_VERSION,
                CONTRACTS.json().toTree(PlanResultPayloads.failed(plan)));
    }
}
