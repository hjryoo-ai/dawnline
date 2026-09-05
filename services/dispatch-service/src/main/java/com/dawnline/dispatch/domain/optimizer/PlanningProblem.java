package com.dawnline.dispatch.domain.optimizer;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 계획 한 번의 입력 전부 (DESIGN.md §6.2).
 *
 * <h2>이 레코드가 곧 불변규칙 5 다</h2>
 * 여기 있는 것만으로 계획이 돌아야 한다 — Spring 도, DB 도, 시계도 밖에 없다. 그래야
 * {@code tools/benchmark} 가 서비스를 띄우지 않고 같은 코드를 그대로 실행할 수 있고, 그 사실이
 * 이 패키지를 프레임워크 비의존으로 둔 유일한 이유다.
 *
 * <p>{@code startedAt} 을 담는 이유도 같다. 순수 함수는 {@code Instant.now()} 를 부르지 않는다
 * (불변규칙 12) — 시각은 입력이고, 그래야 같은 seed 와 같은 입력이 같은 결과를 낸다.
 *
 * @param wave       대상 웨이브
 * @param depot      출발·복귀 캠프
 * @param candidates 계획 대상 주문들
 * @param vehicles   쓸 수 있는 차량들
 * @param rules      적용할 룰 묶음 (시작 시점 스냅샷)
 * @param cost       비용 산식
 * @param distance   거리 제공자
 * @param budget     시간 예산
 * @param startedAt  계획 시작 시각. 라우트의 출발 시각 기준이자 예산 계산의 기준점
 * @param seed       난수 seed. 같으면 결과가 같아야 한다 (불변규칙 12)
 */
public record PlanningProblem(WaveRef wave, CampDepot depot, List<Candidate> candidates,
        List<VehicleSpec> vehicles, RuleSet rules, CostModel cost, DistanceProvider distance,
        PlanningBudget budget, Instant startedAt, long seed) {

    public PlanningProblem {
        Objects.requireNonNull(wave, "wave");
        Objects.requireNonNull(depot, "depot");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(distance, "distance");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(startedAt, "startedAt");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        vehicles = List.copyOf(Objects.requireNonNull(vehicles, "vehicles"));
    }

    /** 계획 마감 시각. */
    public Instant deadline() {
        return budget.deadlineFrom(startedAt);
    }
}
