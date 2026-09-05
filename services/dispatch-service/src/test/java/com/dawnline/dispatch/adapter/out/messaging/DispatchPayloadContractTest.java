package com.dawnline.dispatch.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.Money;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.out.RouteSnapshot;
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
        RoutePlan plan = RoutePlan.request(Ids.newId(), Ids.newId(), Ids.newId(), com.dawnline.common.GeoPoint.of(37.5663, 126.9779));
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

    /** 취소가 실린 개정 — 통합 stop 하나는 죽고, 하나는 일부만 죽었다 (§6.10). */
    private static RouteSnapshot revisedSnapshot() {
        UUID dead = Ids.newId();
        UUID kept = Ids.newId();
        UUID alsoDead = Ids.newId();
        return new RouteSnapshot(Ids.newId(), Ids.newId(), 5_900, 1_600, 17_400, List.of(
                new RouteSnapshot.StopSnapshot(1, List.of(dead), List.of(dead),
                        37.4979, 127.0276, NOW.plusSeconds(600), 90, true),
                new RouteSnapshot.StopSnapshot(2, List.of(kept, alsoDead), List.of(alsoDead),
                        37.4921, 127.0365, NOW.plusSeconds(900), 180, false)));
    }

    @Test
    void 개정_발행이_계약을_지킨다() {
        var payload = RouteAssignedPayload.of(publishedPlan(), revisedSnapshot(), Ids.newId(), 2);

        CONTRACTS.validatePayload(RouteAssignedPayload.EVENT_TYPE,
                RouteAssignedPayload.SCHEMA_VERSION, CONTRACTS.json().toTree(payload));
    }

    @Test
    void 개정_발행은_취소된_stop_을_지우지_않고_stopCount_에_센다() {
        // 부재는 값이 아니다 (ADR-026 결정 4). 취소된 stop 이 배열에서 빠지면 summary 와
        // 배열 길이가 어긋나거나 소비자가 "취소" 와 "이동" 을 구별하지 못하거나 둘 중 하나다.
        var payload = RouteAssignedPayload.of(publishedPlan(), revisedSnapshot(), Ids.newId(), 2);

        assertThat(payload.stops()).hasSize(2);
        assertThat(payload.summary().stopCount()).isEqualTo(2);
        assertThat(payload.stops().getFirst().status()).isEqualTo("CANCELLED");
        assertThat(payload.stops().getFirst().cancelledOrderIds())
                .isEqualTo(payload.stops().getFirst().orderIds());
    }

    @Test
    void 일부만_취소된_stop_은_PLANNED_인_채로_죽은_주문을_이름으로_싣는다() {
        // stop 의 status 만으로는 말할 수 없는 자리다 — tracking 의 shipments 는 order_id 가
        // PK 라(§5.4) 주문 단위로 알아야 한다 (ADR-026 [후속 정정 — Phase 3-6]).
        var payload = RouteAssignedPayload.of(publishedPlan(), revisedSnapshot(), Ids.newId(), 2);

        var merged = payload.stops().getLast();
        assertThat(merged.status()).isEqualTo("PLANNED");
        assertThat(merged.orderIds()).hasSize(2);
        assertThat(merged.cancelledOrderIds()).hasSize(1);
        assertThat(merged.orderIds()).containsAll(merged.cancelledOrderIds());
    }

    @Test
    void 계획_발행에는_취소된_주문이_없다() {
        // PlanPruner 가 발행 직전에 이미 뺐다 (§6.5 6단계). 여기서 값이 생기면 그 단계가 뚫린 것이다.
        var payload = RouteAssignedPayload.of(publishedPlan(), Ids.newId(), Ids.newId(), route(), 1);

        assertThat(payload.stops()).allSatisfy(stop -> {
            assertThat(stop.cancelledOrderIds()).isEmpty();
            assertThat(stop.status()).isEqualTo("PLANNED");
        });
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
        RoutePlan plan = RoutePlan.request(Ids.newId(), Ids.newId(), Ids.newId(), com.dawnline.common.GeoPoint.of(37.5663, 126.9779));
        plan.begin("sweep-greedy-nn", PlanMode.FULL, 1L, 1, NOW);
        plan.fail("NO_CANDIDATES", NOW.plusSeconds(1));

        CONTRACTS.validatePayload(PlanResultPayloads.FAILED_EVENT_TYPE,
                PlanResultPayloads.SCHEMA_VERSION,
                CONTRACTS.json().toTree(PlanResultPayloads.failed(plan)));
    }

    @Test
    void 넓힌_사유도_계약을_지킨다() {
        // 2026-09-05 에 enum 을 넓혔다 (§4.7 — 같은 major 안에서 값 추가는 허용).
        RoutePlan plan = RoutePlan.request(Ids.newId(), Ids.newId(), Ids.newId(), com.dawnline.common.GeoPoint.of(37.5663, 126.9779));
        plan.begin("sweep-greedy-nn", PlanMode.FULL, 1L, 1, NOW);
        plan.fail("RULE_VIOLATION", NOW.plusSeconds(1));

        CONTRACTS.validatePayload(PlanResultPayloads.FAILED_EVENT_TYPE,
                PlanResultPayloads.SCHEMA_VERSION,
                CONTRACTS.json().toTree(PlanResultPayloads.failed(plan)));
    }
}
