package com.dawnline.dispatch.domain.optimizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.domain.optimizer.rule.TimeWindowPenaltyRule;
import com.dawnline.dispatch.domain.optimizer.rule.VehicleCapacityRule;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 부분 재계획의 탐색 (DESIGN.md §6.8 3단계, ADR-048 결정 4).
 *
 * <p>순수 함수다 — DB 도 시계도 없다. 여기서 보는 것은 <strong>무엇을 옮길 수 있고 무엇을 옮길
 * 수 없는가</strong>이고, 그 위의 배선(쿨다운·편차·발행)은 {@code ReplanRouteServiceTest} 가 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("RelocateSearch — relocate 만, 현재 위치 뒤에만")
class RelocateSearchTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);
    private static final CampDepot DEPOT = new CampDepot(Ids.newId(), CAMP);
    private static final TimeWindow SHIFT = new TimeWindow(START, START.plus(Duration.ofHours(10)));
    /** 넉넉한 약속창 — 지각 페널티를 끄고 거리만 보는 테스트가 쓴다. */
    private static final TimeWindow WIDE = new TimeWindow(START, START.plus(Duration.ofHours(9)));

    private final DistanceProvider distance = new HaversineDistance(1.3d, 25.0d);
    private final RelocateSearch search = new RelocateSearch(distance, new CostModel());

    // ------------------------------------------------------------ 얼어 있는 앞자락

    @Test
    void 얼어_있는_앞자락은_옮기지_않는다() {
        // 기사가 이미 닿은 자리다. 옮기면 「이미 배송한 주문을 다른 차가 또 간다」가 된다.
        RelocateSearch.RouteInput source = route(big(), line(1, 2, 3), 3, START);
        RelocateSearch.RouteInput target = route(big(), line(1, 2, 3), 0, START);

        RelocateSearch.Outcome outcome = search.search(RuleSet.empty(), source, List.of(target));

        assertThat(outcome.moved()).isFalse();
        assertThat(outcome.sequences()).isEmpty();
    }

    @Test
    void 삽입은_대상이_지금_있는_자리_뒤에만_한다() {
        // 지나간 자리에 넣으면 기사가 이미 떠난 지점으로 돌아가는 계획이 나온다.
        RelocateSearch.RouteInput source = route(big(), line(2), 0, START);
        // 대상은 1 → 8 로 가는 중이고 1 을 이미 지났다. 옮겨 오는 stop 2 는 «1» 바로 옆이지만
        // 그 앞자락은 얼어 있으므로 뒤에 붙어야 한다.
        RelocateSearch.RouteInput target = route(big(), stops(at(1), at(8)), 1, START);

        RelocateSearch.Outcome outcome = search.search(RuleSet.empty(), source, List.of(target));

        assertThat(outcome.moved()).as("옮길 수 있어야 이 테스트가 뜻을 갖는다").isTrue();
        List<Stop> after = outcome.sequences().get(target.routeId());
        assertThat(after).hasSize(3);
        assertThat(after.getFirst().point()).as("얼어 있는 첫 자리는 그대로다")
                .isEqualTo(at(1).point());
    }

    // ------------------------------------------------------------ 하드 룰 (결정 4 (b))

    @Test
    void 받는_쪽이_하드_룰을_어기면_옮기지_않는다() {
        RuleSet rules = RuleSet.of(List.of(new VehicleCapacityRule("VEHICLE_CAPACITY", 1)), 1);
        RelocateSearch.RouteInput source = route(big(), line(4), 0, START);
        RelocateSearch.RouteInput target = route(tiny(), line(5), 0, START);

        RelocateSearch.Outcome outcome = search.search(rules, source, List.of(target));

        assertThat(outcome.moved()).isFalse();
    }

    @Test
    void 룰이_없으면_같은_이동이_일어난다() {
        // 위 테스트의 음성 짝이다 — 막은 것이 «룰» 이지 거리나 순서가 아니라는 것을 말한다.
        RelocateSearch.RouteInput source = route(big(), line(4), 0, START);
        RelocateSearch.RouteInput target = route(tiny(), line(5), 0, START);

        assertThat(search.search(RuleSet.empty(), source, List.of(target)).moved()).isTrue();
    }

    // ------------------------------------------------------------ 편차 (결정 3)

    @Test
    void 편차가_밀린_시계에서만_지각이_보인다() {
        // 저장 시계로 재면 아무것도 늦지 않아 이 탐색은 언제나 「이득 없음」을 돌려준다.
        // 같은 입력에 시계만 다른 두 번이라 차이를 만든 것이 편차임을 말한다.
        RuleSet rules = RuleSet.of(
                List.of(new TimeWindowPenaltyRule("TIME_WINDOW_PENALTY", 1, 2_000L)), 1);
        TimeWindow tight = new TimeWindow(START, START.plus(Duration.ofMinutes(90)));

        assertThat(search.search(rules, late(tight, Duration.ZERO), List.of(onTime(tight))).moved())
                .as("계획 시계에서는 늦은 것이 없다").isFalse();
        assertThat(search.search(rules, late(tight, Duration.ofHours(2)), List.of(onTime(tight)))
                .moved())
                .as("두 시간 늦은 기사에게는 옮길 값어치가 있다").isTrue();
    }

    // ------------------------------------------------------------ 이득과 상한

    @Test
    void 총비용이_줄지_않으면_옮기지_않는다() {
        // 대상이 멀리 있으면 옮기는 것이 두 라우트 합을 늘린다 — §6.1 목적함수 그대로다.
        // 두 라우트가 직각으로 갈라져 있다 — 한 줄로 늘어놓으면 끝에 붙이는 것이 «공짜» 라
        // 이 테스트가 뜻을 잃는다(끊는 간선과 잇는 간선이 같아진다).
        RelocateSearch.RouteInput source = route(big(), line(1, 2), 0, START);
        RelocateSearch.RouteInput target = route(big(), stops(north(20), north(21)), 0, START);

        assertThat(search.search(RuleSet.empty(), source, List.of(target)).moved()).isFalse();
    }

    @Test
    void 받는_쪽에_같은_지점이_있으면_옮기지_않는다() {
        // 합쳐져야 하는데(§6.5 1단계) 재계획에는 다시 통합할 경로가 없다. 합치지 않고 넣으면
        // 한 라우트가 같은 건물을 두 번 방문한다 (ADR-048 재검토 지점 3).
        RelocateSearch.RouteInput source = route(big(), line(9), 0, START);
        RelocateSearch.RouteInput target = route(big(), stops(at(9), at(10)), 0, START);

        assertThat(search.search(RuleSet.empty(), source, List.of(target)).moved()).isFalse();
    }

    @Test
    void 옮길_stop_이_없으면_빈_결과다() {
        RelocateSearch.RouteInput source = route(big(), List.of(), 0, START);
        RelocateSearch.RouteInput target = route(big(), line(1), 0, START);

        RelocateSearch.Outcome outcome = search.search(RuleSet.empty(), source, List.of(target));

        assertThat(outcome.moves()).isEmpty();
        assertThat(outcome.gainKrw()).isZero();
    }

    @Test
    void 한_번에_옮기는_수에는_상한이_있다() {
        // 「대규모 재편 금지」가 이 상수다 (§6.8 3단계). 상한이 없으면 재계획 한 번이 라우트를
        // 통째로 갈아엎을 수 있고, 그것이 기사에게는 새 계획을 받는 것과 같다.
        List<Stop> many = new ArrayList<>();
        for (int i = 30; i < 50; i++) {
            many.add(at(i));
        }
        RelocateSearch.RouteInput source = route(big(), many, 0, START);
        RelocateSearch.RouteInput target = route(big(), stops(at(45), at(46)), 0, START);

        RelocateSearch.Outcome outcome = search.search(RuleSet.empty(), source, List.of(target));

        assertThat(outcome.moves()).hasSizeLessThanOrEqualTo(RelocateSearch.MAX_MOVES);
    }

    @Test
    void 원_라우트를_후보로_주면_거절한다() {
        // 자기에게 옮기는 것은 이동이 아니라 순서 바꾸기이고, 그것은 §6.8 이 금지한 재편이다.
        RelocateSearch.RouteInput source = route(big(), line(1), 0, START);
        RelocateSearch.RouteInput same = new RelocateSearch.RouteInput(source.routeId(),
                source.vehicle(), DEPOT, source.stops(), 0, START);

        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> search.search(RuleSet.empty(), source, List.of(same))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------ 픽스처

    /** 늦은 라우트 — 동쪽으로 간다. */
    private RelocateSearch.RouteInput late(TimeWindow promised, Duration deviation) {
        return route(big(), List.of(at(5, promised), at(6, promised)), 0, START.plus(deviation));
    }

    /**
     * 제시간에 도는 라우트 — <strong>북쪽</strong>으로 간다.
     *
     * <p>직각으로 두는 이유는 위와 같다: 한 줄이면 옮기는 값이 0 이라 「편차가 없으면 안 옮긴다」
     * 가 거리 때문인지 시계 때문인지 갈리지 않는다.
     */
    private RelocateSearch.RouteInput onTime(TimeWindow promised) {
        return route(big(), List.of(north(5, promised), north(6, promised)), 0, START);
    }

    private static RelocateSearch.RouteInput route(VehicleSpec vehicle, List<Stop> stops,
            int frozen, Instant startAt) {
        return new RelocateSearch.RouteInput(Ids.newId(), vehicle, DEPOT, stops, frozen, startAt);
    }

    private static List<Stop> line(int... steps) {
        List<Stop> stops = new ArrayList<>(steps.length);
        for (int step : steps) {
            stops.add(at(step));
        }
        return stops;
    }

    private static List<Stop> stops(Stop... values) {
        return List.of(values);
    }

    /** 캠프에서 동쪽으로 {@code step} 칸 떨어진 지점. 한 칸은 약 0.9 km 다. */
    private static Stop at(int step) {
        return at(step, WIDE);
    }

    private static Stop at(int step, TimeWindow promised) {
        return stopAt(GeoPoint.of(CAMP.lat(), CAMP.lng() + 0.01d * step), "동", step, promised);
    }

    /** 캠프에서 북쪽으로 {@code step} 칸. 동쪽 줄과 직각이다. */
    private static Stop north(int step) {
        return north(step, WIDE);
    }

    private static Stop north(int step, TimeWindow promised) {
        return stopAt(GeoPoint.of(CAMP.lat() + 0.01d * step, CAMP.lng()), "북", step, promised);
    }

    private static Stop stopAt(GeoPoint point, String arm, int step, TimeWindow promised) {
        return new Stop(point, List.of(OrderId.of(id(arm + step))),
                new Parcel(10_000, 20_000, false, false), promised, 60, 0);
    }

    /** 같은 칸은 같은 주문이다 — 「같은 지점」 테스트가 그것을 쓴다. */
    private static UUID id(String key) {
        return UUID.nameUUIDFromBytes(
                ("stop-" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static VehicleSpec big() {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(1_000_000, 5_000_000),
                new VehicleAttrs("VAN", true, true), SHIFT, VehicleCost.krw(45_000, 600, 250));
    }

    /** 이미 실린 것 하나만으로 가득 차는 차량. */
    private static VehicleSpec tiny() {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(15_000, 30_000),
                new VehicleAttrs("BIKE", true, true), SHIFT, VehicleCost.krw(45_000, 600, 250));
    }
}
