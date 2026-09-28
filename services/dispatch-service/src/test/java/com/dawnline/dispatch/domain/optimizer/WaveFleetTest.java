package com.dawnline.dispatch.domain.optimizer;

import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.GANGNAM;
import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.START;
import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 이 웨이브가 쓸 수 있는 차량 (§6.2, ADR-039 후속) — 함대 판정과 계획이 부르는 <strong>하나의 술어</strong>.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WaveFleetTest {

    /** 새벽 약속창 — START + 1h 부터 네 시간. */
    private static final TimeWindow DAWN = new TimeWindow(START.plus(Duration.ofHours(1)), START.plus(Duration.ofHours(5)));

    @Test
    void 약속창의_합이_끝난_뒤에_근무를_시작하는_차량은_뺀다() {
        VehicleSpec night = vehicle(new TimeWindow(START.minus(Duration.ofHours(1)), START.plus(Duration.ofHours(8))));
        VehicleSpec day = vehicle(new TimeWindow(START.plus(Duration.ofHours(9)), START.plus(Duration.ofHours(22))));

        assertThat(WaveFleet.usable(List.of(night, day), List.of(candidate(DAWN)))).containsExactly(night);
    }

    @Test
    void 일부만_겹쳐도_남긴다_실을_수_있는_stop_이_있다() {
        // 겹침이지 덮음이 아니다 — 약속창 끝 한 시간만 걸친 차량도 그 시간의 stop 은 실을 수 있다.
        VehicleSpec late = vehicle(new TimeWindow(DAWN.end().minus(Duration.ofHours(1)), DAWN.end().plus(Duration.ofHours(8))));

        assertThat(WaveFleet.usable(List.of(late), List.of(candidate(DAWN)))).containsExactly(late);
    }

    @Test
    void 약속창_끝에_시작하는_근무는_뺀다() {
        // 반열린 구간 — 약속창이 끝나는 순각에 시작하면 지각 아닌 stop 이 하나도 없다.
        VehicleSpec atEnd = vehicle(new TimeWindow(DAWN.end(), DAWN.end().plus(Duration.ofHours(8))));

        assertThat(WaveFleet.usable(List.of(atEnd), List.of(candidate(DAWN)))).isEmpty();
    }

    @Test
    void 약속창보다_먼저_끝나는_근무는_뺀다_약속창_시작은_하한이다() {
        // ADR-075 결정 5 — 처음 판의 이 자리는 「남긴다(룰은 이른 도착을 막지 않는다)」였다. 이제 약속창 시작 전에는 기다리므로(§2.2)
        // 창이 열리기 전에 끝나는 근무는 한 stop 도 실을 수 없다. 반열린 구간 — 창 시작에 끝나는 근무도 뺀다.
        VehicleSpec earlier = vehicle(new TimeWindow(DAWN.start().minus(Duration.ofHours(8)), DAWN.start()));

        assertThat(WaveFleet.usable(List.of(earlier), List.of(candidate(DAWN)))).isEmpty();
    }

    @Test
    void 근무_시작은_앞선_계획의_복귀로_밀린다() {
        // ADR-075 결정 3 — available_from = max(근무 시작, 끝나지 않은 발행 라우트 중 가장 늦은 계획 복귀).
        VehicleSpec night = vehicle(new TimeWindow(START.minus(Duration.ofHours(1)), START.plus(Duration.ofHours(8))));
        java.time.Instant back = START.plus(Duration.ofHours(3));

        assertThat(WaveFleet.availableFrom(night, back)).hasValueSatisfying(spec -> {
            assertThat(spec.shift().start()).isEqualTo(back);
            assertThat(spec.shift().end()).isEqualTo(night.shift().end());
            assertThat(spec.id()).isEqualTo(night.id());
        });
        assertThat(WaveFleet.availableFrom(night, null)).contains(night);
        assertThat(WaveFleet.availableFrom(night, START.minus(Duration.ofHours(2)))).as("근무 전에 돌아왔다 — 그대로").contains(night);
        assertThat(WaveFleet.availableFrom(night, night.shift().end())).as("근무 끝에야 돌아온다 — 쓸 수 없다").isEmpty();
    }

    @Test
    void 앞선_계획이_약속창_끝까지_잡은_차는_뺀다() {
        VehicleSpec night = vehicle(new TimeWindow(START.minus(Duration.ofHours(1)), START.plus(Duration.ofHours(8))));
        VehicleSpec busy = WaveFleet.availableFrom(night, DAWN.end()).orElseThrow();

        assertThat(WaveFleet.usable(List.of(busy), List.of(candidate(DAWN)))).isEmpty();
    }

    @Test
    void 합은_가장_이른_시작부터_가장_늦은_끝까지다() {
        // 약속창이 둘로 갈린 웨이브 — 사이의 빈 시간에만 근무하는 차량도 합과는 겹친다. 집합은 차를 빼는 곳이지 stop 을 가르는 곳이
        // 아니다: 그 차가 실제로 실을 stop 이 없으면 룰이 stop 마다 거절한다.
        TimeWindow early = new TimeWindow(START, START.plus(Duration.ofHours(1)));
        TimeWindow later = new TimeWindow(START.plus(Duration.ofHours(4)), START.plus(Duration.ofHours(5)));
        VehicleSpec between = vehicle(new TimeWindow(START.plus(Duration.ofHours(2)), START.plus(Duration.ofHours(3))));

        assertThat(WaveFleet.usable(List.of(between), List.of(candidate(early), candidate(later)))).containsExactly(between);
    }

    @Test
    void 순서를_보존한다() {
        // 예약의 라운드로빈과 동률의 마지막 키가 이 순서를 쓴다(ADR-039 결정 4, 불변규칙 12).
        List<VehicleSpec> fleet = List.of(vehicle(DAWN), vehicle(DAWN), vehicle(DAWN));

        assertThat(WaveFleet.usable(fleet, List.of(candidate(DAWN)))).containsExactlyElementsOf(fleet);
    }

    @Test
    void 후보가_없으면_쓸_차량도_없다() {
        assertThat(WaveFleet.usable(List.of(vehicle(DAWN)), List.of())).isEmpty();
    }

    private static VehicleSpec vehicle(TimeWindow shift) {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(1_000_000, 5_000_000),
                new VehicleAttrs("VAN", false, false), shift, VehicleCost.krw(30_000, 500, 200));
    }

    private static Candidate candidate(TimeWindow promised) {
        return new Candidate(OrderId.of(Ids.newId()), GANGNAM, new Parcel(1_000, 2_000, false, false), promised, 90, 0);
    }
}
