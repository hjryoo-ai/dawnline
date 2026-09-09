package com.dawnline.dispatch.domain.optimizer.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.Stop;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 이웃 표 — 국소 탐색의 후보 생성기.
 *
 * <p>이 표가 틀리면 국소 탐색은 <strong>조용히 나빠진다</strong>. 예외도 없고 위반도 없이,
 * 그냥 개선을 덜 찾는다. 그래서 순서와 개수를 직접 고정한다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("Neighborhood — K-최근접 표")
class NeighborhoodTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(START, START.plus(Duration.ofHours(8)));
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);

    @Test
    void 가까운_순서로_준다() {
        // 동쪽으로 1·2·3·4칸. 0번에서 가까운 순서는 1, 2, 3 이다.
        List<Stop> stops = line(5);

        Neighborhood near = Neighborhood.of(stops, 3);

        assertThat(near.of(0)).containsExactly(1, 2, 3);
        // 가운데는 양쪽이 대칭이라 3·4번째가 동률이다 — 동률의 순서는 어설션에 적지 않는다.
        assertThat(Neighborhood.of(stops, 2).of(2)).containsExactly(1, 3);
    }

    @Test
    void 자기_자신은_이웃이_아니다() {
        Neighborhood near = Neighborhood.of(line(4), 3);

        for (int i = 0; i < 4; i++) {
            assertThat(near.of(i)).doesNotContain(i);
        }
    }

    @Test
    void stop_이_K보다_적으면_있는_만큼만_준다() {
        // 자기를 뺀 수가 상한이다. 배열 길이를 K 로 두면 채우지 못한 칸이 0번 stop 을 가리켜
        // 존재하지 않는 이웃이 생긴다.
        assertThat(Neighborhood.of(line(3), 10).of(0)).hasSize(2);
        assertThat(Neighborhood.of(line(1), 10).of(0)).isEmpty();
    }

    /** 캠프에서 동쪽으로 한 칸씩(약 0.9 km) 늘어선 stop 들. */
    private static List<Stop> line(int count) {
        List<Stop> stops = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            stops.add(new Stop(GeoPoint.of(CAMP.lat(), CAMP.lng() + 0.01d * i),
                    List.of(OrderId.of(Ids.newId())), Parcel.EMPTY, WINDOW, 60, 0));
        }
        return List.copyOf(stops);
    }
}
