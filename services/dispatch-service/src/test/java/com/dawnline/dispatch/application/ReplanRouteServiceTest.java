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
    private final InMemoryDispatchPorts.Transactions transactions = new InMemoryDispatchPorts.Transactions();

    private final ReplanRouteService service = new ReplanRouteService(routes, plans, saved, fleet,
            InMemoryDispatchPorts.rules(RuleSet.of(
                    List.of(new TimeWindowPenaltyRule("TIME_WINDOW_PENALTY", 1, 2_000L)), 1)),
            events, new HaversineDistance(1.3d, 25.0d), metrics, clock, COOLDOWN, TOLERANCE, transactions);

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
    void 닿은_stop_이_없어도_출발이_있으면_출발의_편차로_푼다() {
        // at-risk 는 설계상 출발 지연에서 첫 stop 전에 발화한다(§5.4). 출발이 앵커가 아니던 때는 그 자리가 전부 no-anchor 였다 —
        // 7-4 의 29/32(ADR-072).
        Fixture fixture = fixture();
        routes.row(fixture.atRisk(), 1).status = RouteStopStatus.PLANNED;
        routes.row(fixture.atRisk(), 1).actualAt = null;
        Instant planned = routes.row(fixture.atRisk(), 1).arrival.minus(Duration.ofMinutes(20));
        routes.plannedDeparture.put(fixture.atRisk(), planned);
        routes.markDeparted(fixture.atRisk(), planned.plus(LATE));

        assertThat(service.replan(command(fixture, LATE))).isEqualTo(Outcome.APPLIED);
        assertThat(mismatchCount()).as("앵커 = 출발의 편차 = 페이로드의 편차").isZero();
    }

    @Test
    void 닿은_stop_이_있으면_출발보다_그것이_앵커다() {
        // 닿은 stop 이 더 최근의 사실이다 — 출발 뒤에 벌어진(또는 따라잡은) 만큼이 거기 있다.
        Fixture fixture = fixture();
        Instant planned = routes.row(fixture.atRisk(), 1).arrival.minus(Duration.ofMinutes(20));
        routes.plannedDeparture.put(fixture.atRisk(), planned);
        routes.markDeparted(fixture.atRisk(), planned);       // 정시 출발 — 편차 0

        service.replan(command(fixture, LATE));

        assertThat(mismatchCount()).as("앵커가 출발(0)이었다면 페이로드(LATE)와 갈렸다").isZero();
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
                .containsKey("gainKrw")
                .as("상한 안에서 끝난 탐색에는 칸이 없다").doesNotContainKey("searchTruncated");
    }

    // ------------------------------------------------------------ 평가 상한 (§6.8, 7-0 D4)

    @Test
    void 상한에_걸려_이동을_못_찾으면_no_gain_이_아니라_truncated_다() {
        // 기사가 제시간에 닿았다 — 어느 짝도 이득이 없다. 상한이 넉넉하면 no-gain, 짝 둘(남은 stop 둘 × 받을 라우트 하나)을
        // 다 보기 전에 멈추면 truncated. 「다 봤는데 없다」와 「다 못 봤다」가 한 값으로 접히지 않는다.
        Fixture onTime = fixture();
        routes.row(onTime.atRisk(), 1).actualAt = routes.row(onTime.atRisk(), 1).arrival;
        assertThat(service.replan(command(onTime, Duration.ZERO))).as("전제 — 상한이 넉넉하면 이득 없음")
                .isEqualTo(Outcome.NO_GAIN);

        Fixture again = fixture();
        routes.row(again.atRisk(), 1).actualAt = routes.row(again.atRisk(), 1).arrival;

        // 세는 것은 커밋 뒤의 리스너다(AtRiskListener) — 여기서는 갈래를 본다.
        assertThat(capped(1).replan(command(again, Duration.ZERO))).isEqualTo(Outcome.TRUNCATED);
        assertThat(events.revised).as("옮긴 것이 없다").isEmpty();
    }

    @Test
    void 이동을_찾았어도_상한에_걸렸으면_설명이_그것을_싣는다() {
        // 첫 라운드의 짝 둘을 다 보고 이동 하나를 골랐지만 둘째 라운드는 열지 못했다 — 「더 좋은 이동을 못 본 채 고른 것」.
        Fixture fixture = fixture();

        assertThat(capped(2).replan(command(fixture, LATE))).isEqualTo(Outcome.APPLIED);

        assertThat(saved.explanations).isNotEmpty()
                .allSatisfy(reason -> assertThat(reason.detail()).containsEntry("searchTruncated", true));
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

    // ------------------------------------------------------------ 쓰기는 전제를 다시 본다 (ADR-068)

    @Test
    void 계산하는_동안_옮길_stop_이_끝나면_결과를_버리고_쿨다운을_집지_않는다() {
        // 계산이 PLANNED 로 본 stop 을 쓰기가 그대로 옮기면 끝난 배송이 새 자리의 PLANNED 에 가려진다 — 두 번째 peak-day 의
        // COMPLETED 20건. ADR-026 결정 2 의 「ARRIVED 이후는 거부」를 개정에 넓힌 자리다.
        Fixture fixture = fixture();
        ReplanRouteService racing = during(() -> {
            for (int seq = 2; seq <= 3; seq++) {
                routes.row(fixture.atRisk(), seq).status = RouteStopStatus.COMPLETED;
            }
        });

        assertThat(racing.replan(command(fixture, LATE))).isEqualTo(Outcome.STALE);

        assertThat(events.revised).as("옮기지 않았다 — 개정도 없다").isEmpty();
        assertThat(routes.rowsOf(fixture.spare())).as("받는 쪽은 그대로다").hasSize(2);
        assertThat(routes.coolingDown(fixture.atRisk(), NOW.plus(LATE), COOLDOWN))
                .as("버린 결과는 쿨다운을 집지 않는다 — 다음 at-risk 가 새 사실로 다시 푼다").isFalse();
    }

    @Test
    void 계산하는_동안_라우트가_개정되면_결과를_버린다() {
        // 취소 · 재배정 · 다른 재계획이 순서나 소속을 바꿨다 — 계산한 순서는 지금 라우트의 것이 아니다.
        Fixture fixture = fixture();
        ReplanRouteService racing = during(() -> routes.bumpRevision(fixture.spare()));

        assertThat(racing.replan(command(fixture, LATE))).isEqualTo(Outcome.STALE);
        assertThat(events.revised).isEmpty();
    }

    @Test
    void 옮기는_것은_stop_행이다_id_와_상태가_그대로_간다() {
        // 주문을 새 stop 으로 옮기고 원래 행을 지우면 그 행의 락을 기다리던 상태 반영이 0 행을 고치고, 합쳐진 stop 은 주문마다
        // 흩어진다(ADR-068 결정 3). 행이 옮겨 가면 둘 다 없다.
        Fixture fixture = fixture();
        List<UUID> before = routes.rowsOf(fixture.atRisk()).stream().map(row -> row.id).toList();

        assertThat(service.replan(command(fixture, LATE))).isEqualTo(Outcome.APPLIED);

        List<UUID> arrived = routes.rowsOf(fixture.spare()).stream().map(row -> row.id)
                .filter(before::contains).toList();
        assertThat(arrived).as("받는 쪽에 원 라우트의 행이 그대로 있다").isNotEmpty();
        assertThat(routes.rowsOf(fixture.spare())).as("받는 쪽의 행 수 = 원래 둘 + 옮겨 온 행 — 새로 만든 행이 없다")
                .hasSize(2 + arrived.size());
    }

    @Test
    void 계산하는_동안_받을_라우트가_끝나면_결과를_버린다() {
        // 받을 라우트의 마지막 stop 이 계산 중에 끝났다 — 기사는 복귀했다. 넣으면 그 stop 은 아무도 가지 않고 차량은 비활성화되지
        // 못한다(ADR-068 후속 A). 끝남은 revision 을 올리지 않으므로 revision 대조로는 보이지 않는다 — 이 테스트가 보는 자리다.
        Fixture fixture = fixture();
        ReplanRouteService racing = during(() -> routes.rowsOf(fixture.spare())
                .forEach(row -> row.status = RouteStopStatus.COMPLETED));

        assertThat(racing.replan(command(fixture, LATE))).isEqualTo(Outcome.STALE);

        assertThat(events.revised).isEmpty();
        assertThat(routes.rowsOf(fixture.spare())).as("끝난 라우트는 아무것도 받지 않았다").hasSize(2);
    }

    @Test
    void 게이트가_쓰기를_건너뛰면_결과가_없고_아무것도_쓰지_않는다() {
        // 같은 eventId 의 재전달 — 계산은 한 번 더 돌았고 게이트가 버린다(ADR-064 결정 2 와 같다). 셀 것이 없다.
        Fixture fixture = fixture();

        assertThat(service.replan(command(fixture, LATE), write -> false)).isEmpty();
        assertThat(events.revised).isEmpty();
        assertThat(routes.coolingDown(fixture.atRisk(), NOW.plus(LATE), COOLDOWN)).isFalse();
    }

    // ------------------------------------------------------------ 후보가 없는 경우

    @Test
    void 받을_라우트가_없으면_아무것도_하지_않는다() {
        Fixture alone = single();

        assertThat(service.replan(command(alone, LATE))).isEqualTo(Outcome.NO_CANDIDATE);
    }

    @Test
    void 끝난_라우트는_받을_라우트가_아니다() {
        // 끝나지 않은 stop 이 하나도 없으면 끝났다 — 보존 · 비활성화 409 와 같은 판정이다(ADR-068 후속 A). 두 번째 peak-day 에서
        // 앞 stop 을 전부 끝낸 라우트 둘이 stop 을 받았고, 그 stop 은 스캔되지 않았다.
        Fixture fixture = fixture();
        routes.rowsOf(fixture.spare()).forEach(row -> row.status = RouteStopStatus.COMPLETED);

        assertThat(service.replan(command(fixture, LATE))).isEqualTo(Outcome.NO_CANDIDATE);
        assertThat(routes.rowsOf(fixture.spare())).hasSize(2);
    }

    @Test
    void 빈_라우트도_받을_라우트가_아니다() {
        // 재배정 · 재계획이 비운 라우트 — 끝나지 않은 stop 이 없으니 409 도 그 차량의 비활성화를 허락한다. 받으면 기사 없는 stop 이다.
        Fixture fixture = fixture();
        routes.clear(fixture.spare());

        assertThat(service.replan(command(fixture, LATE))).isEqualTo(Outcome.NO_CANDIDATE);
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
                COOLDOWN, TOLERANCE, transactions);
    }

    /** 계산 안에서 한 번 {@code race} 를 돌리는 같은 서비스 — 거리를 처음 물을 때다. 계산과 쓰기 사이의 경합을 흉내 낸다. */
    private ReplanRouteService during(Runnable race) {
        HaversineDistance real = new HaversineDistance(1.3d, 25.0d);
        java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean(true);
        return new ReplanRouteService(routes, plans, saved, fleet,
                InMemoryDispatchPorts.rules(RuleSet.of(
                        List.of(new TimeWindowPenaltyRule("TIME_WINDOW_PENALTY", 1, 2_000L)), 1)),
                events, (from, to) -> {
                    if (armed.compareAndSet(true, false)) {
                        race.run();
                    }
                    return real.between(from, to);
                }, metrics, clock, COOLDOWN, TOLERANCE, transactions);
    }

    /** 평가 상한을 준 같은 서비스. */
    private ReplanRouteService capped(int maxEvaluations) {
        return new ReplanRouteService(routes, plans, saved, fleet,
                InMemoryDispatchPorts.rules(RuleSet.of(
                        List.of(new TimeWindowPenaltyRule("TIME_WINDOW_PENALTY", 1, 2_000L)), 1)),
                events, new HaversineDistance(1.3d, 25.0d), metrics, clock, COOLDOWN, TOLERANCE, transactions,
                maxEvaluations);
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
