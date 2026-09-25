package com.dawnline.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.in.ReplanRouteUseCase.Outcome;
import com.dawnline.dispatch.application.port.in.ReplanRouteUseCase.ReplanCommand;
import com.dawnline.dispatch.application.port.out.VehicleCatalog;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.PlanMode;
import com.dawnline.dispatch.domain.PlanModeReason;
import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.dispatch.domain.RouteStopStatus;
import com.dawnline.dispatch.domain.optimizer.Explanation;
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.rule.TimeWindowPenaltyRule;
import com.dawnline.observability.DawnlineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@code delivery.at-risk} → §6.8 부분 재계획 (ADR-048).
 *
 * <p>탐색 자체는 {@code RelocateSearchTest} 가 본다. 여기서 보는 것은 <em>그 바깥</em>이다 —
 * 쿨다운, 편차를 어디서 읽는가, 무엇을 발행하고 무엇을 남기는가.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ReplanRouteService — 입력은 자기 DB 다")
class ReplanRouteServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-06T01:00:00Z");
    private static final Duration LATE = Duration.ofHours(2);
    private static final Duration COOLDOWN = Duration.ofMinutes(10);
    private static final Duration TOLERANCE = Duration.ofSeconds(60);
    private static final UUID CAMP_ID = Ids.newId();
    private static final GeoPoint CAMP = InMemoryDispatchPorts.CAMP;
    /** 약속창은 계획대로면 넉넉하고 두 시간 늦으면 빠듯하다 — 그 차이가 이 테스트의 축이다. */
    private static final TimeWindow PROMISED = new TimeWindow(NOW, NOW.plus(Duration.ofMinutes(90)));

    private final InMemoryDispatchPorts.Candidates candidates =
            new InMemoryDispatchPorts.Candidates();
    private final InMemoryDispatchPorts.Plans plans = new InMemoryDispatchPorts.Plans();
    private final InMemoryDispatchPorts.Events events = new InMemoryDispatchPorts.Events();
    private final InMemoryDispatchPorts.Routes saved = new InMemoryDispatchPorts.Routes();
    private final InMemoryDispatchPorts.CancellableRoutes routes =
            new InMemoryDispatchPorts.CancellableRoutes(candidates);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DispatchMetrics metrics = new DispatchMetrics(registry);
    private final VehicleCatalog fleet = InMemoryDispatchPorts.fleet(2, NOW);
    private final Clock clock = Clock.fixed(NOW.plus(LATE), ZoneOffset.UTC);

    private final ReplanRouteService service = new ReplanRouteService(routes, plans, saved, fleet,
            InMemoryDispatchPorts.rules(RuleSet.of(
                    List.of(new TimeWindowPenaltyRule("TIME_WINDOW_PENALTY", 1, 2_000L)), 1)),
            events, new HaversineDistance(1.3d, 25.0d), metrics, clock, COOLDOWN, TOLERANCE);

    // ------------------------------------------------------------ 쿨다운 (결정 7)

    @Test
    void 쿨다운_안이면_아무것도_하지_않는다() {
        // 두 at-risk 는 eventId 가 달라 processed_events 가 막지 못한다 — 막는 것은 이 컬럼이다.
        Fixture fixture = fixture();
        assertThat(service.replan(command(fixture, LATE))).isEqualTo(Outcome.APPLIED);
        int published = events.revised.size();

        Outcome again = service.replan(command(fixture, LATE));

        assertThat(again).isEqualTo(Outcome.COOLDOWN);
        assertThat(events.revised).hasSize(published);
    }

    // ------------------------------------------------------------ 편차의 출처 (결정 1·2)

    @Test
    void 닿은_stop_이_없으면_편차를_모르고_아무것도_하지_않는다() {
        // 모름은 0 이 아니다. 0 으로 두고 돌리면 출발 지연 라우트가 「이득 없음」으로 조용히
        // 닫히고, 그 사실이 no-gain 과 구별되지 않는다.
        Fixture fixture = fixture();
        routes.row(fixture.atRisk(), 1).status = RouteStopStatus.PLANNED;
        routes.row(fixture.atRisk(), 1).actualAt = null;

        assertThat(service.replan(command(fixture, LATE))).isEqualTo(Outcome.NO_ANCHOR);
        assertThat(events.revised).isEmpty();
    }

    @Test
    void 페이로드의_편차와_갈리면_센다() {
        // 버리지도 않고 입력으로 쓰지도 않는다 — 견준다. 갈린다는 것은 tracking 과 dispatch 가
        // 같은 라우트를 다르게 보고 있다는 뜻이고, 그 사실이 먼저 필요하다.
        Fixture fixture = fixture();

        service.replan(command(fixture, Duration.ZERO));

        assertThat(mismatchCount()).isEqualTo(1.0d);
    }

    @Test
    void 허용_오차_안이면_세지_않는다() {
        // 두 값은 서로 다른 시각 원천에서 온다. 초 단위 일치를 요구하면 이 카운터는 늘 켜져
        // 있어 아무 말도 하지 않는다.
        Fixture fixture = fixture();

        service.replan(command(fixture, LATE.minusSeconds(30)));

        assertThat(mismatchCount()).isZero();
    }

    // ------------------------------------------------------------ 결과 (결정 4 (c) · 5 · 6)

    @Test
    void 옮기면_두_라우트_모두_개정을_올리고_발행한다() {
        // §5.3 운영자 재배정과 같은 경로다. 떠난 쪽은 시간이 당겨져 지각 판정이 바뀌고,
        // 그 사실이 발행에 실려야 한다.
        Fixture fixture = fixture();

        assertThat(service.replan(command(fixture, LATE))).isEqualTo(Outcome.APPLIED);

        assertThat(events.revised).hasSize(2);
        assertThat(events.revisions).containsExactly(2, 2);
        assertThat(events.revised).extracting(snapshot -> snapshot.routeId())
                .containsExactlyInAnyOrder(fixture.atRisk(), fixture.spare());
    }

    @Test
    void 옮긴_주문은_받은_라우트의_stop_으로_옮겨_간다() {
        Fixture fixture = fixture();

        service.replan(command(fixture, LATE));

        List<UUID> moved = routes.loadPositionedStops(fixture.spare()).stream()
                .flatMap(stop -> stop.stop().orderIds().stream())
                .map(orderId -> orderId.value())
                .toList();
        assertThat(moved).as("빈 손으로 돌아오면 이 테스트 아래가 전부 무의미하다")
                .hasSizeGreaterThan(2);
    }

    @Test
    void applied_는_어디서_어디로_얼마인지를_남긴다() {
        // §6.3 이 룰을 데이터로 둔 이유가 「왜 이 주문이 이 차인가」이고, 운영자가 그것을 가장
        // 많이 묻는 자리가 재계획이다 — 기사에게서 전화가 오는 자리이기 때문이다.
        Fixture fixture = fixture();

        service.replan(command(fixture, LATE));

        assertThat(saved.explanations).isNotEmpty();
        Explanation first = saved.explanations.getFirst();
        assertThat(first.ruleName()).isEqualTo(Explanation.RELOCATED_BY_AT_RISK);
        assertThat(first.outcome()).isEqualTo(Explanation.Outcome.ASSIGNED);
        assertThat(first.detail())
                .containsEntry("fromRouteId", fixture.atRisk().toString())
                .containsEntry("toRouteId", fixture.spare().toString())
                .containsKey("gainKrw");
    }

    @Test
    void 저장은_계획_시계로_한다() {
        // 편차를 저장까지 반영하면 actual_at − planned_arrival 에서 빼는 쪽이 밀려 다음 편차의
        // 기준선이 사라진다 — 두 번째 at-risk 에서 자기 편차를 0 으로 보게 된다 (결정 3).
        Fixture fixture = fixture();
        service.replan(command(fixture, LATE));
        Instant afterFirst = routes.row(fixture.atRisk(), 1).arrival;
        Duration seen = routes.lastSettledStop(fixture.atRisk()).orElseThrow().deviation();

        // 쿨다운 뒤에 한 번 더 — 그 사이에 기사는 아무 데도 닿지 않았다.
        later().replan(command(fixture, LATE));

        assertThat(routes.row(fixture.atRisk(), 1).arrival)
                .as("닿은 stop 의 계획 도착은 재계획을 지나도 움직이지 않는다")
                .isEqualTo(afterFirst);
        assertThat(routes.lastSettledStop(fixture.atRisk()).orElseThrow().deviation())
                .as("그래서 두 번째 at-risk 도 같은 편차를 본다 — 0 이 아니다")
                .isEqualTo(seen)
                .isGreaterThanOrEqualTo(LATE);
    }

    // ------------------------------------------------------------ 후보가 없는 경우

    @Test
    void 받을_라우트가_없으면_아무것도_하지_않는다() {
        Fixture fixture = fixture();
        routes.clear(fixture.spare());

        // 빈 라우트도 후보이긴 하다(미출발 차량과 같다) — 그래서 이 테스트는 계획에 라우트가
        // 하나뿐인 경우로 만든다.
        Fixture alone = single();

        assertThat(service.replan(command(alone, LATE))).isEqualTo(Outcome.NO_CANDIDATE);
        assertThat(fixture.atRisk()).isNotEqualTo(alone.atRisk());
    }

    @Test
    void 남은_stop_이_없으면_아무것도_하지_않는다() {
        Fixture fixture = fixture();
        for (int seq = 1; seq <= 3; seq++) {
            routes.row(fixture.atRisk(), seq).status = RouteStopStatus.COMPLETED;
            routes.row(fixture.atRisk(), seq).actualAt = NOW.plus(LATE);
        }

        assertThat(service.replan(command(fixture, LATE))).isEqualTo(Outcome.NO_CANDIDATE);
    }

    // ------------------------------------------------------------ 픽스처

    private record Fixture(UUID atRisk, UUID spare) {
    }

    /** 쿨다운이 지난 뒤의 같은 서비스. 시계만 다르다 (불변규칙 12). */
    private ReplanRouteService later() {
        return new ReplanRouteService(routes, plans, saved, fleet,
                InMemoryDispatchPorts.rules(RuleSet.of(
                        List.of(new TimeWindowPenaltyRule("TIME_WINDOW_PENALTY", 1, 2_000L)), 1)),
                events, new HaversineDistance(1.3d, 25.0d), metrics,
                Clock.fixed(NOW.plus(LATE).plus(COOLDOWN).plusSeconds(1), ZoneOffset.UTC),
                COOLDOWN, TOLERANCE);
    }

    private double mismatchCount() {
        return registry.counter(DawnlineMetrics.AT_RISK_DEVIATION_MISMATCH.meterName()).count();
    }

    private static ReplanCommand command(Fixture fixture, Duration deviation) {
        return new ReplanCommand(fixture.atRisk(), CAMP_ID, NOW.plus(deviation),
                deviation.toSeconds());
    }

    /**
     * 동쪽으로 가는 늦은 라우트 하나와 북쪽으로 가는 멀쩡한 라우트 하나.
     *
     * <p>직각으로 두는 이유: 한 줄로 늘어놓으면 끝에 붙이는 값이 0 이라 「편차 때문에 옮겼다」와
     * 「거리 때문에 옮겼다」가 갈리지 않는다.
     */
    private Fixture fixture() {
        RoutePlan plan = plan();
        List<UUID> vehicles = fleet.availableAt(CAMP_ID, NOW).stream()
                .map(vehicle -> vehicle.id().value()).toList();

        UUID atRisk = routes.route(plan.id(), vehicles.getFirst(),
                arm(true, 5, 6, 7));
        UUID spare = routes.route(plan.id(), vehicles.getLast(), arm(false, 5, 6));

        // 기사는 첫 지점에 두 시간 늦게 닿았다. 이것이 dispatch 가 아는 유일한 편차다.
        routes.row(atRisk, 1).status = RouteStopStatus.COMPLETED;
        routes.row(atRisk, 1).actualAt = routes.row(atRisk, 1).arrival.plus(LATE);
        return new Fixture(atRisk, spare);
    }

    /** 라우트가 하나뿐인 계획 — 받을 곳이 없다. */
    private Fixture single() {
        RoutePlan plan = plan();
        UUID vehicleId = fleet.availableAt(CAMP_ID, NOW).getFirst().id().value();
        UUID atRisk = routes.route(plan.id(), vehicleId, arm(true, 5, 6, 7));
        routes.row(atRisk, 1).status = RouteStopStatus.COMPLETED;
        routes.row(atRisk, 1).actualAt = routes.row(atRisk, 1).arrival.plus(LATE);
        return new Fixture(atRisk, atRisk);
    }

    private List<InMemoryDispatchPorts.CancellableRoutes.StopRow> arm(boolean east, int... steps) {
        List<InMemoryDispatchPorts.CancellableRoutes.StopRow> stops = new ArrayList<>();
        int seq = 1;
        for (int step : steps) {
            GeoPoint point = east
                    ? GeoPoint.of(CAMP.lat(), CAMP.lng() + 0.01d * step)
                    : GeoPoint.of(CAMP.lat() + 0.01d * step, CAMP.lng());
            stops.add(new InMemoryDispatchPorts.CancellableRoutes.StopRow(seq, point, 60,
                    NOW.plus(Duration.ofMinutes(15L * seq)), List.of(candidate(point)), PROMISED));
            seq++;
        }
        return stops;
    }

    private RoutePlan plan() {
        RoutePlan plan = RoutePlan.request(Ids.newId(), Ids.newId(), CAMP_ID, CAMP);
        plans.insertIfAbsent(plan);
        plan.begin("baseline-nn", PlanMode.FULL, PlanModeReason.NONE, 1L, 1, NOW);
        plans.update(plan);
        return plan;
    }

    private UUID candidate(GeoPoint point) {
        UUID orderId = Ids.newId();
        candidates.put(DispatchCandidate.load(orderId, Ids.newId(), CAMP_ID, null, point,
                10_000, 20_000, false, false, PROMISED, 60, false, 0, NOW));
        return orderId;
    }
}
