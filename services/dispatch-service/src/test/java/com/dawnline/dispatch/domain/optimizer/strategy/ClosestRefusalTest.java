package com.dawnline.dispatch.domain.optimizer.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.Capacity;
import com.dawnline.dispatch.domain.optimizer.Feasibility;
import com.dawnline.dispatch.domain.optimizer.HardRule;
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.RouteAccumulator;
import com.dawnline.dispatch.domain.optimizer.RouteState;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleAttrs;
import com.dawnline.dispatch.domain.optimizer.VehicleCost;
import com.dawnline.dispatch.domain.optimizer.VehicleId;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 미배정 설명은 <strong>통과에 가장 가까웠던 거절</strong>이다 (§6.3, ADR-039 후속).
 *
 * <h2>이 테스트가 없던 동안 무슨 일이 있었나</h2>
 * 사유는 차량 코드 순 <em>마지막</em> 라우트의 거절이었다. 첫 {@code peak-day} 의 새벽 웨이브에서 그 차는 열 캠프 모두 주간 냉장
 * 트럭이었고, 사유가 늘 「09:00 출발 + 이동 − 07:00」의 지각이었다 — 그 차에 대해서만 참이었다. 집합을 고친 뒤에는 마지막 차가
 * 야간 일반 밴이 되어 사유가 「냉장 차량이 아니다」로 바뀌었다 — 이번에도 그 차에 대해서만 참이다.
 *
 * <h2>순서 운을 없애는 방법</h2>
 * {@link GreedyAssignerTieBreakTest} 와 같다 — 라우트 목록을 <strong>양쪽 순서로</strong> 넣어 본다. 「마지막」과 「가장 가까운」이
 * 한 순서에서 우연히 같으면 이 테스트가 옛 코드를 통과시킨다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("closestRefusalFor — 가장 가까웠던 거절")
class ClosestRefusalTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);
    private static final Stop STOP = new Stop(GeoPoint.of(37.4979, 127.0276), List.of(OrderId.of(Ids.newId())),
            new Parcel(1_000, 1_000, true, false), new TimeWindow(START, START.plus(Duration.ofHours(4))), 60, 0);

    private final VehicleSpec a = vehicle();
    private final VehicleSpec b = vehicle();
    private final VehicleSpec c = vehicle();

    /** 차량마다 정해진 거절을 내는 룰 — 판정을 대본으로 고정해 순서만 본다. */
    private record ScriptedRule(String name, int priority, Map<VehicleId, Long> refuses) implements HardRule {

        @Override
        public Feasibility check(Stop stop, VehicleSpec vehicle, RouteState state) {
            Long excess = refuses.get(vehicle.id());
            return excess == null ? Feasibility.ok() : Feasibility.violated(name, name + " @" + vehicle.id().value(), excess);
        }
    }

    @Test
    void 평가_순서에서_더_멀리_간_거절이_라우트_순서와_무관하게_이긴다() {
        // a 는 앞 룰(냉장)에 걸렸고 b 는 뒤 룰(지각)에 걸렸다 — b 는 냉장을 통과했다. 폭은 b 가 훨씬 크지만 다른 룰이라 견주지 않는다.
        RuleSet rules = RuleSet.of(List.of(
                new ScriptedRule("cold-chain", 10, Map.of(a.id(), 0L)),
                new ScriptedRule("late-hard-limit", 30, Map.of(b.id(), 500L))), 1);

        assertThat(GreedyAssigner.closestRefusalFor(STOP, routes(rules, a, b)).ruleName()).isEqualTo("late-hard-limit");
        assertThat(GreedyAssigner.closestRefusalFor(STOP, routes(rules, b, a)).ruleName()).isEqualTo("late-hard-limit");
    }

    @Test
    void 같은_룰이면_위반_폭이_작은_거절이_이긴다() {
        RuleSet rules = RuleSet.of(List.of(
                new ScriptedRule("late-hard-limit", 30, Map.of(a.id(), 70L, b.id(), 5L, c.id(), 30L))), 1);

        assertThat(GreedyAssigner.closestRefusalFor(STOP, routes(rules, a, b, c)).excess()).isEqualTo(5L);
        assertThat(GreedyAssigner.closestRefusalFor(STOP, routes(rules, c, b, a)).excess()).isEqualTo(5L);
    }

    @Test
    void 동률이면_앞_라우트의_거절이다() {
        // 차량 순서가 곧 재현성이다(불변규칙 12) — 같은 입력이 같은 설명을 낸다.
        RuleSet rules = RuleSet.of(List.of(new ScriptedRule("max-stops", 20, Map.of(a.id(), 0L, b.id(), 0L))), 1);

        assertThat(GreedyAssigner.closestRefusalFor(STOP, routes(rules, a, b)).reason()).endsWith(a.id().value().toString());
        assertThat(GreedyAssigner.closestRefusalFor(STOP, routes(rules, b, a)).reason()).endsWith(b.id().value().toString());
    }

    @Test
    void 라우트가_없으면_실을_차량이_없다() {
        // 이 웨이브가 쓸 수 있는 차량이 0대인 경우(§6.2 — 집합을 거른 결과).
        assertThat(GreedyAssigner.closestRefusalFor(STOP, List.of()).ruleName()).isEqualTo("no-feasible-vehicle");
    }

    private static List<RouteAccumulator> routes(RuleSet rules, VehicleSpec... vehicles) {
        return java.util.Arrays.stream(vehicles)
                .map(vehicle -> new RouteAccumulator(rules, vehicle, new CampDepot(Ids.newId(), CAMP),
                        new HaversineDistance(1.3d, 25.0d), START))
                .toList();
    }

    private static VehicleSpec vehicle() {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(400_000, 1_200_000),
                new VehicleAttrs("VAN", true, false), new TimeWindow(START, START.plus(Duration.ofHours(10))),
                VehicleCost.krw(45_000, 600, 250));
    }
}
