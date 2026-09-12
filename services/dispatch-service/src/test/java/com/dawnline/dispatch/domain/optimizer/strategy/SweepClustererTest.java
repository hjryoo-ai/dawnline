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
import java.util.OptionalInt;
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
        return ring(count, Parcel.EMPTY);
    }

    private static List<Stop> ring(int count, Parcel parcel) {
        List<Stop> stops = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            stops.add(at(i * (360.0d / count), parcel));
        }
        return stops;
    }

    @Test
    void 극각_순서로_정렬한다() {
        // 입력을 일부러 뒤섞는다 — 순서가 아니라 각으로 자르는지 본다.
        List<Stop> stops = List.of(at(270, Parcel.EMPTY), at(0, Parcel.EMPTY), at(90, Parcel.EMPTY));

        List<List<Stop>> clusters = clusterer.cluster(stops, DEPOT, HUGE, 1, OptionalInt.empty());

        assertThat(clusters).singleElement().satisfies(cluster ->
                assertThat(cluster).containsExactly(stops.get(1), stops.get(2), stops.get(0)));
    }

    @Test
    void 클러스터_수는_총수요와_용량에서_나온다() {
        // 40 stop × 100 kg = 4 t, 차 한 대가 1 t → 최소 4 클러스터. 손으로 검산된다.
        Capacity oneTon = new Capacity(1_000_000, 100_000_000);
        List<Stop> stops = ring(40, new Parcel(100_000, 1, false, false));

        List<List<Stop>> clusters = clusterer.cluster(stops, DEPOT, oneTon, 8, OptionalInt.empty());

        assertThat(clusters).hasSize(4);
        assertThat(clusters).allSatisfy(cluster -> assertThat(cluster).hasSize(10));
    }

    @Test
    void 한_차가_다_실을_수_있으면_클러스터도_하나다() {
        // 차 한 대 몫으로 자르던 규칙은 클러스터 수를 차량 수와 같게 만들었고, 뒤의 탐욕 배정이
        // 클러스터마다 새 차를 열어 **언제나 전 차량을 굴렸다** — 고정비가 총비용 격차의
        // 31~96% 였다 (docs/benchmarks/phase3-baseline.md).
        List<List<Stop>> clusters = clusterer.cluster(ring(40), DEPOT, HUGE, 8, OptionalInt.empty());

        assertThat(clusters).hasSize(1);
    }

    @Test
    void 클러스터_수는_차량_수를_넘지_않는다() {
        // 클러스터가 차보다 많으면 남는 것이 이미 실은 차에 얹혀 지그재그가 된다.
        // 40 stop × 1 kg = 40 kg, 차 한 대가 15 kg → 3 클러스터, 차량도 3대.
        Capacity fifteenKg = new Capacity(15_000, 100_000_000);
        List<Stop> stops = ring(40, new Parcel(1_000, 1, false, false));

        assertThat(clusterer.cluster(stops, DEPOT, fifteenKg, 3, OptionalInt.empty()))
                .hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void 용량을_넘으면_목표_크기_전에도_자른다() {
        Capacity tight = new Capacity(2_500, 100_000_000);
        List<Stop> stops = List.of(
                at(0, new Parcel(1_000, 1, false, false)),
                at(10, new Parcel(1_000, 1, false, false)),
                at(20, new Parcel(1_000, 1, false, false)));

        List<List<Stop>> clusters = clusterer.cluster(stops, DEPOT, tight, 1, OptionalInt.empty());

        assertThat(clusters).hasSize(2);
        assertThat(clusters.getFirst()).hasSize(2);
    }

    @Test
    void 권역_경계는_목표_크기에_가까울_때만_자르는_이유가_된다() {
        // "경계에서 자르기 우선" 은 "자를 때가 됐으면 경계에서" 라는 뜻이다. 경계마다 자르면
        // 클러스터가 차량 수의 몇 배로 부서지고, 남는 것이 이미 실은 차에 얹혀 지그재그가 된다.
        Capacity oneTon = new Capacity(1_000_000, 100_000_000);
        List<Stop> stops = ring(40, new Parcel(100_000, 1, false, false));

        assertThat(clusterer.cluster(stops, DEPOT, oneTon, 8, OptionalInt.empty()))
                .hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void stop_상한이_있으면_그_축으로도_자른다() {
        // 중량·부피는 한 대 몫인데(HUGE) stop 수가 상한의 네 배다. 무는 축이 stop 슬롯일 때
        // 클러스터러가 그것을 보지 않으면 차 네 대 몫을 한 묶음으로 들고 간다 ([ADR-041]).
        List<List<Stop>> clusters =
                clusterer.cluster(ring(40), DEPOT, HUGE, 8, OptionalInt.of(10));

        assertThat(clusters).hasSizeGreaterThanOrEqualTo(4);
        assertThat(clusters).allSatisfy(cluster -> assertThat(cluster).hasSizeLessThanOrEqualTo(10));
    }

    @Test
    void 상한이_없으면_그_축도_없다() {
        // 제외한 것이 왜 제외인지 — 상한을 말하는 룰이 없으면 stop 축은 «무한» 이지 «1» 이
        // 아니다. 같은 입력이 위 테스트에서는 네 조각, 여기서는 한 덩어리다.
        assertThat(clusterer.cluster(ring(40), DEPOT, HUGE, 8, OptionalInt.empty())).hasSize(1);
    }

    @Test
    void stop_축이_말해도_클러스터는_차량_수를_넘지_않는다() {
        // 상한을 함께 없앤 변형이 peak 에서 클러스터 121개(차량 88대)를 만들고 +1,507,476원을
        // 냈다 — 남는 클러스터가 이미 실은 차에 얹혀 부챗살 여럿을 오가기 때문이다.
        assertThat(clusterer.cluster(ring(100), DEPOT, HUGE, 3, OptionalInt.of(10)))
                .hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void 같은_입력이면_같은_클러스터가_나온다() {
        List<Stop> stops = ring(24);

        assertThat(clusterer.cluster(stops, DEPOT, HUGE, 3, OptionalInt.empty()))
                .isEqualTo(clusterer.cluster(stops, DEPOT, HUGE, 3, OptionalInt.empty()));
    }

    @Test
    void 빈_목록은_빈_결과다() {
        assertThat(clusterer.cluster(List.of(), DEPOT, HUGE, 3, OptionalInt.empty())).isEmpty();
    }

    @Test
    void 차량이_0_이면_거부한다() {
        assertThatThrownBy(() -> clusterer.cluster(ring(4), DEPOT, HUGE, 0, OptionalInt.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 모든_stop_이_정확히_한_클러스터에_들어간다() {
        List<Stop> stops = ring(37);

        List<Stop> flattened = clusterer.cluster(stops, DEPOT, HUGE, 5, OptionalInt.empty()).stream()
                .flatMap(List::stream).toList();

        assertThat(flattened).containsExactlyInAnyOrderElementsOf(stops);
    }
}
