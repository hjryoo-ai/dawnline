package com.dawnline.dispatch.domain.optimizer.strategy;

import com.dawnline.common.Money;
import com.dawnline.dispatch.domain.optimizer.DistanceProvider;
import com.dawnline.dispatch.domain.optimizer.RouteAccumulator;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.Travel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 시간창을 보는 최근접 이웃 (DESIGN.md §6.5 4단계).
 *
 * <h2>{@code baseline-nn} 과 무엇이 다른가</h2>
 * 베이스라인은 <strong>거리만</strong> 본다. 여기서는 <strong>이동비 + 지각페널티</strong>가
 * 최소인 stop 을 고른다. 지각페널티는 소프트 룰이 매긴 값을 그대로 쓴다 — 룰이 데이터라는
 * 원칙이 여기서도 지켜진다(§6.3). 시퀀서가 자기 페널티 표를 따로 갖지 않는다.
 *
 * <h2>대기시간을 세지 않는 이유 (§6.5 4단계 정정)</h2>
 * §6.5 는 "거리 + <em>대기시간</em> + 지각페널티" 라고 적었지만, <strong>이 모델에는 대기가
 * 없다</strong>. {@link com.dawnline.dispatch.domain.optimizer.RouteState} 는 도착 즉시 서비스를
 * 시작하고 §2.2·§6.3 은 <em>지각만</em> 벌한다 — 조기 배송은 허용된다. 그래서 대기를 값으로
 * 매기면 일어나지도 않는 일에 돈을 물리는 것이 된다.
 *
 * <p>그 대가는 컸다. 대기를 넣으면 뒤 약속창의 stop 이 크게 불리해져 라우트가 <strong>약속창
 * 순서로 같은 부챗살을 세 번 훑는다.</strong> 측정: small 에서 거리 604,745 m → 407,353 m,
 * 총비용 2,374,377 → 1,557,071, 미배정 21 → 6.
 *
 * <p>대기가 실제 비용이 되려면 먼저 라우트 모델이 그것을 표현해야 한다(도착을 창 시작으로 미루고,
 * 그 시간을 근무창 판정에 넣는 것). 그건 조기 배송을 금지하겠다는 <em>정책</em> 결정이라 §2.2 를
 * 먼저 고쳐야 하는 일이고, 여기서 조용히 할 일이 아니다.
 */
public final class NearestNeighborSequencer {

    /**
     * 넣을 수 있는 것을 순서대로 넣고, 넣지 못한 것을 돌려준다.
     *
     * @param route     쌓을 라우트
     * @param candidates 이 라우트에 넣어 볼 stop 들
     * @param distance  거리 제공자
     */
    public Set<Stop> sequence(RouteAccumulator route, List<Stop> candidates,
            DistanceProvider distance) {

        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(distance, "distance");
        Set<Stop> remaining = new LinkedHashSet<>(candidates);

        while (!remaining.isEmpty()) {
            Stop best = null;
            long bestCost = Long.MAX_VALUE;
            for (Stop stop : remaining) {
                if (!route.check(stop).feasible()) {
                    continue;
                }
                long cost = costOf(route, stop, distance);
                // 엄격한 부등호라 동률이면 먼저 온 것이 이긴다 (결정적).
                if (cost < bestCost) {
                    best = stop;
                    bestCost = cost;
                }
            }
            if (best == null) {
                break;
            }
            route.append(best);
            remaining.remove(best);
        }
        return remaining;
    }

    /** 이동비(거리 + 시간) + 지각 페널티를 원 단위로 합친 값. */
    private long costOf(RouteAccumulator route, Stop stop, DistanceProvider distance) {
        Travel travel = distance.between(route.state().at(), stop.point());
        var cost = route.state().vehicle().cost();

        long distanceKrw = Math.multiplyExact(cost.perKm().krw(), (long) travel.meters()) / 1000L;
        long driveKrw = Math.multiplyExact(cost.perMin().krw(), (long) travel.seconds()) / 60L;
        Money penalty = route.penaltyOf(stop);
        return distanceKrw + driveKrw + penalty.krw();
    }

    /** 남은 것을 목록으로. 호출부가 순서를 유지한 채 다루기 편하게. */
    public static List<Stop> asList(Set<Stop> stops) {
        return new ArrayList<>(stops);
    }
}
