package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.Money;
import java.util.Objects;

/**
 * 차량 비용 산식 (DESIGN.md §6.1 비용식, §6.4).
 *
 * <pre>
 * cost(route) = fixed(v) + dist_km·perKm(v) + dur_min·perMin(v)
 * </pre>
 *
 * <h2>파라미터가 없는 이유</h2>
 * §6.4 는 "파라미터는 {@code dispatch_rules} 와 {@code vehicles} 에서 오며 코드에 상수를 두지
 * 않는다" 고 했다. 차량 쪽 파라미터는 {@link VehicleCost} 가, 룰 쪽 페널티는 {@link SoftRule} 이
 * 들고 있으므로 이 클래스에 남는 상태가 없다. 그래도 §6.2 가 이것을 <em>주입</em>하도록 정한 이유는
 * 비용 산식 자체가 캠프별로 달라질 수 있기 때문이고, 그때 파라미터가 붙을 자리가 여기다.
 *
 * <h2>내림</h2>
 * m → km, 초 → 분 환산은 <strong>곱한 뒤에 나눈다</strong>. 먼저 나누면 1 km 미만 구간의 비용이
 * 통째로 사라져 짧은 stop 이 공짜가 된다. 나머지는 버린다(내림) — 원 단위 아래가 없는 통화이고,
 * 두 전략을 같은 규칙으로 재는 한 비교에는 영향이 없다.
 */
public final class CostModel {

    /**
     * 라우트 하나의 차량 비용. 빈 라우트는 0 이다 — 굴리지 않은 차에 고정비를 물면 미배정보다
     * 비싸 보여서 최적화가 차를 억지로 채운다.
     *
     * @param vehicle   차량
     * @param distanceM 총 이동 거리(m). 캠프 복귀 포함
     * @param durationS 총 소요 시간(초). 이동 + 서비스
     * @param stopCount 배치된 stop 수
     */
    public Money routeCost(VehicleSpec vehicle, int distanceM, int durationS, int stopCount) {
        Objects.requireNonNull(vehicle, "vehicle");
        if (stopCount <= 0) {
            return Money.ZERO;
        }
        VehicleCost cost = vehicle.cost();
        long distance = Math.multiplyExact(cost.perKm().krw(), (long) distanceM) / 1000L;
        long duration = Math.multiplyExact(cost.perMin().krw(), (long) durationS) / 60L;
        return cost.fixed().plus(Money.krw(distance)).plus(Money.krw(duration));
    }
}
