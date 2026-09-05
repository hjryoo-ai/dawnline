package com.dawnline.dispatch.domain.optimizer;

import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.CITY_HALL;
import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.GANGNAM;
import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.START;
import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.YEOUIDO;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RouteStateTest {

    private final CampDepot depot = OptimizerFixtures.depot();
    private final VehicleSpec vehicle = OptimizerFixtures.vehicle();
    private final DistanceProvider distance = OptimizerFixtures.distance();

    private RouteState empty() {
        return RouteState.empty(vehicle, depot, distance, START);
    }

    @Test
    void 빈_라우트는_캠프에_있고_시각은_출발_시각이다() {
        RouteState state = empty();

        assertThat(state.isEmpty()).isTrue();
        assertThat(state.at()).isEqualTo(depot.point());
        assertThat(state.time()).isEqualTo(START);
        assertThat(state.load()).isEqualTo(Parcel.EMPTY);
        assertThat(state.distanceM()).isZero();
    }

    @Test
    void stop_을_붙이면_새_상태가_나오고_원본은_그대로다() {
        // 개선 단계가 여러 배치를 시험하고 버리므로, 제자리 변경은 되돌리기 코드를 부른다.
        RouteState before = empty();
        Stop stop = OptimizerFixtures.stop(GANGNAM);

        RouteState after = before.append(stop);

        assertThat(before.stopCount()).isZero();
        assertThat(after.stopCount()).isEqualTo(1);
        assertThat(after).isNotSameAs(before);
    }

    @Test
    void 시각은_이동_시간과_서비스_시간을_함께_민다() {
        RouteState before = empty();
        Stop stop = OptimizerFixtures.stop(GANGNAM);
        Travel travel = distance.between(before.at(), stop.point());

        RouteState after = before.append(stop);

        // 현재 시각은 도착이 아니라 출발 시각이다 — 다음 stop 은 여기서부터 간다.
        assertThat(after.time())
                .isEqualTo(START.plusSeconds(travel.seconds() + stop.serviceSeconds()));
        assertThat(after.stops().getFirst().arrival())
                .isEqualTo(START.plusSeconds(travel.seconds()));
    }

    @Test
    void 방문_순번은_1_부터_연속으로_붙는다() {
        RouteState state = empty();
        for (var point : java.util.List.of(GANGNAM, YEOUIDO, CITY_HALL)) {
            Stop stop = OptimizerFixtures.stop(point);
            state = state.append(stop);
        }

        assertThat(state.stops()).extracting(PlannedStop::seq).containsExactly(1, 2, 3);
    }

    @Test
    void 적재와_거리는_누적된다() {
        RouteState state = empty();
        Stop first = OptimizerFixtures.stop(GANGNAM, new Parcel(1_000, 2_000, false, false), 0);
        Stop second = OptimizerFixtures.stop(YEOUIDO, new Parcel(500, 800, true, false), 0);

        state = state.append(first);
        int afterFirst = state.distanceM();
        state = state.append(second);

        assertThat(state.load()).isEqualTo(new Parcel(1_500, 2_800, true, false));
        assertThat(state.distanceM()).isGreaterThan(afterFirst);
    }

    @Test
    void 지나온_권역을_방문_순서대로_센다() {
        // ZONE_AFFINITY 소프트 룰이 이 집합의 크기를 본다.
        RouteState state = empty();
        for (var point : java.util.List.of(GANGNAM, YEOUIDO)) {
            Stop stop = OptimizerFixtures.stop(point);
            state = state.append(stop);
        }

        assertThat(state.zones())
                .containsExactly(GANGNAM.geohash5(), YEOUIDO.geohash5());
    }

    @Test
    void 붙이지_않고도_도착_시각을_알_수_있다() {
        // TIME_WINDOW_LIMIT 은 배정 "전에" 판정해야 한다 — 붙여 보고 되돌리는 코드를 부르지 않는다.
        RouteState state = empty();
        Stop stop = OptimizerFixtures.stop(GANGNAM);

        Instant peeked = state.arrivalIfAppended(stop);

        assertThat(state.stopCount()).as("엿보기는 상태를 바꾸지 않는다").isZero();
        assertThat(peeked).isEqualTo(state.append(stop).stops().getFirst().arrival());
    }

    @Test
    void 복귀_시각은_캠프까지_돌아오는_시간을_포함한다() {
        // SHIFT_WINDOW 가 보는 값이다. 마지막 stop 도착 시각이 아니다.
        RouteState state = empty().append(OptimizerFixtures.stop(GANGNAM));

        assertThat(state.returnTime()).isAfter(state.time());
    }

    @Test
    void 붙였을_때의_복귀_시각도_엿볼_수_있다() {
        RouteState state = empty();
        Stop stop = OptimizerFixtures.stop(GANGNAM);

        assertThat(state.returnTimeIfAppended(stop))
                .isEqualTo(state.append(stop).returnTime());
    }

    @Test
    void 복귀를_포함한_거리는_누적보다_크다() {
        RouteState state = empty().append(OptimizerFixtures.stop(GANGNAM));

        assertThat(state.distanceWithReturn()).isGreaterThan(state.distanceM());
    }

    @Test
    void 빈_라우트의_복귀는_출발_그대로다() {
        // 캠프에 서 있으므로 돌아갈 거리가 0 이다.
        assertThat(empty().returnTime()).isEqualTo(START);
        assertThat(empty().distanceWithReturn()).isZero();
    }

    @Test
    void 같은_권역의_두_stop_은_권역을_하나로_센다() {
        RouteState state = empty();
        Stop first = OptimizerFixtures.stop(GANGNAM);
        Stop second = OptimizerFixtures.stop(GANGNAM);

        state = state.append(first);
        state = state.append(second);

        assertThat(state.zones()).hasSize(1);
    }
}
