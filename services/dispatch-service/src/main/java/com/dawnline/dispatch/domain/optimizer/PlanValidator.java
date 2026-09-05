package com.dawnline.dispatch.domain.optimizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 최종 라우트에 하드 룰을 다시 돌리는 방어선 (DESIGN.md §6.5 6단계).
 *
 * <h2>왜 두 번 검사하는가</h2>
 * 배치할 때 이미 검사했는데 또 하는 이유는 <strong>개선 단계 때문</strong>이다. 2-opt·Or-opt·
 * relocate 는 이미 놓인 stop 의 순서와 소속을 바꾸고, 순서가 바뀌면 도착 시각이 바뀌고, 도착 시각이
 * 바뀌면 {@code TIME_WINDOW_LIMIT} 과 {@code SHIFT_WINDOW} 판정이 바뀐다. 개선 코드의 버그가
 * <em>조용히</em> 하드 룰을 어긴 라우트를 내보내는 것을 여기서 막는다.
 *
 * <p>그래서 이 검사는 "통과하면 좋고" 가 아니라 <strong>통과하지 못하면 발행하지 않는</strong>
 * 관문이다. 위반이 나오면 그것은 데이터 문제가 아니라 코드 버그다.
 */
public final class PlanValidator {

    /**
     * 라우트 전부를 처음부터 재생하며 하드 룰을 검사한다.
     *
     * <p>재생하는 이유는 {@link RouteState} 가 누적값이기 때문이다 — 마지막 상태만 보면 "3번째
     * stop 에서 이미 용량을 넘었다" 를 볼 수 없다.
     *
     * @param problem 계획 입력 (룰·차량·캠프·거리)
     * @param result  검사할 결과
     * @return 위반 목록. 비어 있으면 통과다
     */
    public List<Violation> validate(PlanningProblem problem, PlanResult result) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(result, "result");

        List<Violation> violations = new ArrayList<>();
        for (PlannedRoute route : result.routes()) {
            VehicleSpec vehicle = vehicleOf(problem, route.vehicle());
            if (vehicle == null) {
                violations.add(new Violation(route.vehicle(), 0,
                        Feasibility.violated("unknown-vehicle",
                                "계획에 없는 차량이 라우트에 배정됐습니다: " + route.vehicle())));
                continue;
            }
            replay(problem, route, vehicle, violations);
        }
        return List.copyOf(violations);
    }

    private void replay(PlanningProblem problem, PlannedRoute route, VehicleSpec vehicle,
            List<Violation> violations) {

        RouteState state = RouteState.empty(vehicle, problem.depot(), problem.distance(),
                problem.startedAt());
        for (PlannedStop planned : route.stops()) {
            Stop stop = planned.stop();
            Feasibility feasibility = problem.rules().check(stop, vehicle, state);
            if (!feasibility.feasible()) {
                violations.add(new Violation(route.vehicle(), planned.seq(), feasibility));
            }
            state = state.append(stop);
        }
    }

    private VehicleSpec vehicleOf(PlanningProblem problem, VehicleId id) {
        return problem.vehicles().stream()
                .filter(vehicle -> vehicle.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    /**
     * 최종 검증에서 걸린 위반 하나.
     *
     * @param vehicle     위반이 난 라우트의 차량
     * @param seq         위반이 난 stop 의 방문 순번. 라우트 전체의 문제면 0
     * @param feasibility 위반 내용
     */
    public record Violation(VehicleId vehicle, int seq, Feasibility feasibility) {

        public Violation {
            Objects.requireNonNull(vehicle, "vehicle");
            Objects.requireNonNull(feasibility, "feasibility");
        }
    }
}
