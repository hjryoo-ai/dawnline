package com.dawnline.dispatch.domain.optimizer.strategy;

import com.dawnline.dispatch.domain.optimizer.Stop;
import java.util.List;
import java.util.Objects;

/**
 * stop 마다 「가까운 stop 몇 개」를 미리 뽑아 둔 표 (국소 탐색의 후보 생성기).
 *
 * <h2>왜 필요한가</h2>
 * 국소 탐색의 이웃 정의를 제한 없이 두면 2-opt 만으로도 라우트 하나에 O(n²) 개의 후보가 나오고,
 * inter-route 는 O(n²) 이 전체 stop 에 걸린다. {@code large}(통합 후 약 2,600 stop)에서 그것은
 * 한 패스에 수백만 번의 라우트 재구성이고, 30초 예산으로는 <strong>한 패스도 끝나지 않는다.</strong>
 *
 * <p>제한의 근거는 기하다. 개선하는 이동은 거의 언제나 <strong>짧은 간선을 새로 만든다</strong> —
 * 멀리 떨어진 두 stop 을 잇는 2-opt 가 비용을 줄이는 경우는 드물다. 그래서 후보를 "새로 생기는
 * 간선이 K-최근접 안" 인 것으로 좁힌다. 이건 근사이고, 그 대가는 <em>이 표 밖의 개선을 못 본다</em>
 * 는 것이다. 대신 한 패스가 O(n·K) 로 끝나 예산 안에서 여러 번 돈다.
 *
 * <h2>거리는 근사로 잰다</h2>
 * 이웃 <em>선정</em>에만 쓰므로 하버사인이 필요 없다. 캠프 반경 8 km 안에서는 등거리 원통 도법
 * (위도 보정한 평면 근사)의 순서가 하버사인의 순서와 사실상 같고, {@code sin/cos/atan2} 없이
 * 곱셈 몇 번으로 끝난다. <strong>비용 판정에는 이 값을 절대 쓰지 않는다</strong> — 그건
 * {@code DistanceProvider} 의 일이다.
 */
final class Neighborhood {

    /** 위도 1도 ≈ 111.32 km. 8 km 반경에서는 평면 근사로 충분하다. */
    private static final double METERS_PER_DEGREE_LAT = 111_320.0d;

    private final int[][] near;

    private Neighborhood(int[][] near) {
        this.near = near;
    }

    /**
     * 표를 만든다.
     *
     * @param stops 전체 stop (인덱스가 곧 stop 의 이름이 된다)
     * @param k     stop 하나당 이웃 수
     */
    static Neighborhood of(List<Stop> stops, int k) {
        Objects.requireNonNull(stops, "stops");
        int n = stops.size();
        double[] x = new double[n];
        double[] y = new double[n];
        double cos = n == 0 ? 1.0d : Math.cos(Math.toRadians(stops.get(0).point().lat()));
        for (int i = 0; i < n; i++) {
            x[i] = stops.get(i).point().lng() * METERS_PER_DEGREE_LAT * cos;
            y[i] = stops.get(i).point().lat() * METERS_PER_DEGREE_LAT;
        }

        int[][] near = new int[n][];
        int size = Math.min(k, Math.max(n - 1, 0));
        int[] best = new int[size];
        double[] bestDist = new double[size];
        for (int i = 0; i < n; i++) {
            int filled = 0;
            double worst = Double.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                double dx = x[i] - x[j];
                double dy = y[i] - y[j];
                double d = dx * dx + dy * dy;
                if (filled == size && d >= worst) {
                    continue;               // 흔한 경우 — 비교 하나로 끝난다
                }
                // 삽입 정렬. size 가 10 남짓이라 힙보다 이쪽이 빠르고 결과가 정렬돼 나온다.
                int at = filled < size ? filled++ : size - 1;
                while (at > 0 && bestDist[at - 1] > d) {
                    bestDist[at] = bestDist[at - 1];
                    best[at] = best[at - 1];
                    at--;
                }
                bestDist[at] = d;
                best[at] = j;
                worst = filled == size ? bestDist[size - 1] : Double.MAX_VALUE;
            }
            near[i] = java.util.Arrays.copyOf(best, filled);
        }
        return new Neighborhood(near);
    }

    /**
     * 이 stop 에 가까운 stop 들 (가까운 순).
     *
     * @param stopIndex stop 인덱스
     */
    int[] of(int stopIndex) {
        return near[stopIndex];
    }
}
