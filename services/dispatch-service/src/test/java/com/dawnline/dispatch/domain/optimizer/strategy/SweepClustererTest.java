package com.dawnline.dispatch.domain.optimizer.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.Capacity;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.Stop;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SweepClustererTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(START, START.plus(Duration.ofHours(6)));
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);
    private static final CampDepot DEPOT = new CampDepot(Ids.newId(), CAMP);
    private static final Capacity HUGE = new Capacity(100_000_000, 100_000_000);

    private final SweepClusterer clusterer = new SweepClusterer(4);

    /** 캠프에서 방위각 {@code degrees} 방향, 약 1 km 지점. */
    private static Stop at(double degrees, Parcel parcel) {
        double radians = Math.toRadians(degrees);
        GeoPoint point = GeoPoint.of(CAMP.lat() + 0.009d * Math.cos(radians),
                CAMP.lng() + 0.009d * Math.sin(radians));
        return new Stop(point, List.of(OrderId.of(Ids.newId())), parcel, WINDOW, 60, 0);
    }

    private static List<Stop> ring(int count) {
        List<Stop> stops = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            stops.add(at(i * (360.0d / count), Parcel.EMPTY));
        }
        return stops;
    }

    @Test
    void 극각_순서로_정렬한다() {
        // 입력을 일부러 뒤섞는다 — 순서가 아니라 각으로 자르는지 본다.
        List<Stop> stops = List.of(at(270, Parcel.EMPTY), at(0, Parcel.EMPTY), at(90, Parcel.EMPTY));

        List<List<Stop>> clusters = clusterer.cluster(stops, DEPOT, HUGE, 1);

        assertThat(clusters).singleElement().satisfies(cluster ->
                assertThat(cluster).containsExactly(stops.get(1), stops.get(2), stops.get(0)));
    }

    @Test
    void 클러스터_수가_차량_수에_맞춰진다() {
        // 목표 크기는 차 한 대 몫이다. 이것이 없으면 큰 트럭 하나가 전부를 삼켜 스윕이
        // 아무 일도 하지 않는다 — 측정에서 거리가 베이스라인의 두 배로 나왔다.
        List<List<Stop>> clusters = clusterer.cluster(ring(40), DEPOT, HUGE, 4);

        assertThat(clusters).hasSize(4);
        assertThat(clusters).allSatisfy(cluster -> assertThat(cluster).hasSize(10));
    }

    @Test
    void 용량을_넘으면_목표_크기_전에도_자른다() {
        Capacity tight = new Capacity(2_500, 100_000_000);
        List<Stop> stops = List.of(
                at(0, new Parcel(1_000, 1, false, false)),
                at(10, new Parcel(1_000, 1, false, false)),
                at(20, new Parcel(1_000, 1, false, false)));

        List<List<Stop>> clusters = clusterer.cluster(stops, DEPOT, tight, 1);

        assertThat(clusters).hasSize(2);
        assertThat(clusters.getFirst()).hasSize(2);
    }

    @Test
    void 권역_경계는_목표_크기에_가까울_때만_자르는_이유가_된다() {
        // "경계에서 자르기 우선" 은 "자를 때가 됐으면 경계에서" 라는 뜻이다. 경계마다 자르면
        // 클러스터가 차량 수의 몇 배로 부서지고, 남는 것이 이미 실은 차에 얹혀 지그재그가 된다.
        // 40개를 차량 2대(목표 20)로 자르면 경계가 여러 번 바뀌어도 2~3개여야 한다.
        List<List<Stop>> clusters = clusterer.cluster(ring(40), DEPOT, HUGE, 2);

        assertThat(clusters).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void 같은_입력이면_같은_클러스터가_나온다() {
        List<Stop> stops = ring(24);

        assertThat(clusterer.cluster(stops, DEPOT, HUGE, 3))
                .isEqualTo(clusterer.cluster(stops, DEPOT, HUGE, 3));
    }

    @Test
    void 빈_목록은_빈_결과다() {
        assertThat(clusterer.cluster(List.of(), DEPOT, HUGE, 3)).isEmpty();
    }

    @Test
    void 차량이_0_이면_거부한다() {
        assertThatThrownBy(() -> clusterer.cluster(ring(4), DEPOT, HUGE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 모든_stop_이_정확히_한_클러스터에_들어간다() {
        List<Stop> stops = ring(37);

        List<Stop> flattened = clusterer.cluster(stops, DEPOT, HUGE, 5).stream()
                .flatMap(List::stream).toList();

        assertThat(flattened).containsExactlyInAnyOrderElementsOf(stops);
    }
}
