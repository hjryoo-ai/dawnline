package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.dispatch.domain.PlanMode;
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
 * @param mode       실행 모드 (§6.7). {@code FAST} 가 생략하는 것은 §6.5 <strong>5단계</strong>
 *                   하나이고, 그 사실은 여기 입력으로 들어와야 순수 함수로 남는다 — 전략이
 *                   설정을 읽거나 스스로 바쁨을 판단하면 같은 입력이 다른 답을 낸다
 * @param budgetFactor <strong>개선 예산</strong>에 곱하는 계수 (0 초과 1 이하, §6.7 사다리).
 *                   개선 예산은 {@code budget.total() − 앞 단계가 쓴 시간}이고, 여기에 이 값을
 *                   곱한 만큼만 §6.5 5단계가 돈다. {@code 1.0} 이 정상이다.
 *                   <strong>예산이 조이지 않으면 이 값은 아무것도 하지 않는다</strong> — 개선
 *                   단계는 보통 국소 최적이나 「개선 폭 &lt; 0.1%」로 먼저 멈추기 때문이다
 * @param startedAt  계획 시작 시각. 라우트의 출발 시각 기준이자 예산 계산의 기준점
 * @param seed       난수 seed. 같으면 결과가 같아야 한다 (불변규칙 12)
 */
public record PlanningProblem(WaveRef wave, CampDepot depot, List<Candidate> candidates,
        List<VehicleSpec> vehicles, RuleSet rules, CostModel cost, DistanceProvider distance,
        PlanningBudget budget, PlanMode mode, double budgetFactor, Instant startedAt,
        long seed) {

    public PlanningProblem {
        Objects.requireNonNull(wave, "wave");
        Objects.requireNonNull(depot, "depot");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(distance, "distance");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(mode, "mode");
        if (!(budgetFactor > 0.0d) || budgetFactor > 1.0d) {
            throw new IllegalArgumentException(
                    "개선 예산 계수는 0 초과 1 이하여야 합니다: " + budgetFactor);
        }
        Objects.requireNonNull(startedAt, "startedAt");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        vehicles = List.copyOf(Objects.requireNonNull(vehicles, "vehicles"));
    }

    /** 계획 마감 시각. */
    public Instant deadline() {
        return budget.deadlineFrom(startedAt);
    }

    /**
     * §6.5 5단계(국소 탐색)를 돌리는가.
     *
     * <p><strong>FAST 가 생략하는 것은 이 단계 하나다.</strong> 재삽입은 개선이 아니라 값싼
     * 탐욕이라 FAST 에서도 돈다(ADR-028). 통합·클러스터링·배정·시퀀싱은 계획이 <em>존재하기</em>
     * 위한 단계라 애초에 생략할 수 있는 것이 아니다.
     */
    public boolean runsImprovement() {
        return mode != PlanMode.FAST;
    }

    /**
     * §6.5 5단계에 실제로 주는 시간.
     *
     * <p><strong>열화는 사다리다</strong>(§6.7, ADR-034 후속 정정). 계획이 예산을 다 쓴 것과
     * 처리량이 모자란 것은 다른 신호이고, 처방도 달라야 한다 — 앞의 것은 이 값을 줄이고
     * (개선을 덜 한다), 뒤의 것만 {@link #runsImprovement()} 를 끈다(개선을 안 한다).
     *
     * @param elapsedNanos 앞 단계들이 이미 쓴 시간
     */
    public long improvementNanos(long elapsedNanos) {
        long remaining = budget.total().toNanos() - elapsedNanos;
        return remaining <= 0L ? 0L : (long) (remaining * budgetFactor);
    }
}
