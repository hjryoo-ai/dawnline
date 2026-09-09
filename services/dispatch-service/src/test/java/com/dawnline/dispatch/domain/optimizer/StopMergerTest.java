package com.dawnline.dispatch.domain.optimizer;

import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.GANGNAM;
import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.START;
import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.YEOUIDO;
import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class StopMergerTest {

    private static final TimeWindow MORNING =
            new TimeWindow(START, START.plus(Duration.ofHours(4)));
    private static final TimeWindow AFTERNOON =
            new TimeWindow(START.plus(Duration.ofHours(4)), START.plus(Duration.ofHours(8)));

    private static Candidate at(GeoPoint point, TimeWindow window, Parcel parcel, int priority) {
        return new Candidate(OrderId.of(Ids.newId()), point, parcel, window, 90, priority);
    }

    @Test
    void 같은_지점_같은_창은_하나로_묶인다() {
        List<Stop> stops = StopMerger.merge(List.of(
                at(GANGNAM, MORNING, new Parcel(1_000, 2_000, false, false), 0),
                at(GANGNAM, MORNING, new Parcel(500, 800, false, false), 0)));

        assertThat(stops).singleElement().satisfies(stop -> {
            assertThat(stop.orderCount()).isEqualTo(2);
            assertThat(stop.parcel()).isEqualTo(new Parcel(1_500, 2_800, false, false));
            assertThat(stop.serviceSeconds()).as("하차·전달은 주문마다 일어난다").isEqualTo(180);
        });
    }

    @Test
    void 창이_다르면_묶이지_않는다() {
        // 같은 건물의 오전 주문과 오후 주문은 한 번에 배송할 수 없다.
        List<Stop> stops = StopMerger.merge(List.of(
                at(GANGNAM, MORNING, Parcel.EMPTY, 0),
                at(GANGNAM, AFTERNOON, Parcel.EMPTY, 0)));

        assertThat(stops).hasSize(2);
    }

    @Test
    void 지점이_다르면_묶이지_않는다() {
        List<Stop> stops = StopMerger.merge(List.of(
                at(GANGNAM, MORNING, Parcel.EMPTY, 0),
                at(YEOUIDO, MORNING, Parcel.EMPTY, 0)));

        assertThat(stops).hasSize(2);
    }

    @Test
    void 함께_탈_수_없는_주문은_같은_자리라도_다른_stop_이다() {
        // **2026-09-09 에 뒤집힌 성질이다** (ADR-033). 이전에는 통합이 제약을 <em>전파</em>했다 —
        // 한 건이라도 위험물이면 stop 전체가 위험물이 되고, 옆의 평범한 주문이 함께 위험물
        // 차량을 기다렸다. large 에서 미배정 stop 18개가 주문 89건을 안고 있었던 것이 그 값이다.
        //
        // 통합의 질문은 "한 번에 배송할 수 있는가" 이고, 차량이 막으면 답은 아니오다.
        List<Stop> stops = StopMerger.merge(List.of(
                at(GANGNAM, MORNING, new Parcel(1, 1, false, false), 0),
                at(GANGNAM, MORNING, new Parcel(1, 1, true, true), 0)));

        assertThat(stops).as("좌표도 창도 같지만 제약이 다르다").hasSize(2);
        assertThat(stops).anySatisfy(stop -> {
            assertThat(stop.parcel().requiresCold()).isFalse();
            assertThat(stop.parcel().hazmat()).isFalse();
        });
        assertThat(stops).anySatisfy(stop -> {
            assertThat(stop.parcel().requiresCold()).isTrue();
            assertThat(stop.parcel().hazmat()).isTrue();
        });
    }

    @Test
    void 제약이_같으면_여전히_묶인다() {
        // 위 테스트가 "통합이 아예 안 된다" 로 통과하지 않는다는 것을 보인다.
        List<Stop> stops = StopMerger.merge(List.of(
                at(GANGNAM, MORNING, new Parcel(1, 1, true, false), 0),
                at(GANGNAM, MORNING, new Parcel(2, 2, true, false), 0)));

        assertThat(stops).singleElement().satisfies(stop -> {
            assertThat(stop.orderCount()).isEqualTo(2);
            assertThat(stop.parcel().requiresCold()).isTrue();
            assertThat(stop.parcel().weightG()).isEqualTo(3);
        });
    }

    @Test
    void 우선도는_최댓값이다() {
        // VIP 한 명이 섞인 stop 을 뒤로 미루면 그 VIP 가 늦는다.
        List<Stop> stops = StopMerger.merge(List.of(
                at(GANGNAM, MORNING, Parcel.EMPTY, 0),
                at(GANGNAM, MORNING, Parcel.EMPTY, 3),
                at(GANGNAM, MORNING, Parcel.EMPTY, 1)));

        assertThat(stops).singleElement()
                .satisfies(stop -> assertThat(stop.priority()).isEqualTo(3));
    }

    @Test
    void 입력_순서를_유지한다() {
        // seed 가 같으면 결과가 같아야 한다 (불변규칙 12). 순서가 흔들리면 최근접 탐색의 동률
        // 처리도 흔들린다.
        Candidate first = at(YEOUIDO, MORNING, Parcel.EMPTY, 0);
        Candidate second = at(GANGNAM, MORNING, Parcel.EMPTY, 0);

        assertThat(StopMerger.merge(List.of(first, second)))
                .extracting(Stop::point).containsExactly(YEOUIDO, GANGNAM);
    }

    @Test
    void 대표점은_첫_후보의_좌표다() {
        // 무게중심을 쓰면 아무도 살지 않는 좌표가 나온다 — 기사가 갈 곳은 실재해야 한다.
        GeoPoint near = GeoPoint.of(GANGNAM.lat() + 0.0002d, GANGNAM.lng());
        List<Stop> stops = StopMerger.merge(List.of(
                at(GANGNAM, MORNING, Parcel.EMPTY, 0),
                at(near, MORNING, Parcel.EMPTY, 0)));

        assertThat(stops).singleElement().satisfies(stop -> {
            assertThat(stop.orderCount()).as("같은 geohash7 이면 묶인다").isEqualTo(2);
            assertThat(stop.point()).isEqualTo(GANGNAM);
        });
    }

    @Test
    void 빈_목록은_빈_결과다() {
        assertThat(StopMerger.merge(List.of())).isEmpty();
    }
}
