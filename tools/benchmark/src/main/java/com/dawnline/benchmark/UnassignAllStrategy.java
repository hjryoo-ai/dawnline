package com.dawnline.benchmark;

import com.dawnline.common.Money;
import com.dawnline.dispatch.domain.optimizer.Candidate;
import com.dawnline.dispatch.domain.optimizer.DispatchStrategy;
import com.dawnline.dispatch.domain.optimizer.Explanation;
import com.dawnline.dispatch.domain.optimizer.Feasibility;
import com.dawnline.dispatch.domain.optimizer.PlanMetrics;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.Unassigned;
import java.util.ArrayList;
import java.util.List;

/**
 * 아무것도 배정하지 않는 전략 — <strong>비용의 상한</strong>.
 *
 * <h2>왜 있는가</h2>
 * 둘이다.
 *
 * <ol>
 *   <li><strong>하네스를 검증한다.</strong> 전략이 하나도 없는 상태에서 만들어진 벤치마크 도구는
 *       스스로 돌아가는지 알 수 없다. 이 전략의 결과는 손으로 계산할 수 있으므로
 *       (비용 = 미배정 페널티의 합) 파이프라인·지표·리포트가 맞는지 확인된다.</li>
 *   <li><strong>어떤 전략도 이보다 나빠서는 안 된다.</strong> §6.9 의 비교표에서 이 값은
 *       "계획을 아예 하지 않았을 때" 이고, 그것보다 비싼 계획은 계획이 아니다.</li>
 * </ol>
 *
 * <p>그래서 이것은 {@code baseline-nn} 이 아니다. 베이스라인은 "가장 단순한 <em>진짜</em> 계획"
 * 이고 이것은 "계획하지 않음" 이다. 서비스가 아니라 벤치마크 도구에 두는 이유도 그것이다.
 */
public final class UnassignAllStrategy implements DispatchStrategy {

    /** 전략 이름. */
    public static final String NAME = "unassign-all";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PlanResult plan(PlanningProblem problem) {
        List<Unassigned> unassigned = new ArrayList<>(problem.candidates().size());
        List<Explanation> explanations = new ArrayList<>(problem.candidates().size());
        Money total = Money.ZERO;

        Feasibility reason = Feasibility.violated(NAME, "이 전략은 배정하지 않습니다 (비용 상한)");
        for (Candidate candidate : problem.candidates()) {
            unassigned.add(Unassigned.from(candidate.id(), reason));
            explanations.add(Explanation.unassigned(candidate.id(), reason, 0));
            total = total.plus(problem.rules().unassignedPenalty(Stop.of(candidate)));
        }

        PlanMetrics metrics = new PlanMetrics(0, 0, unassigned.size(), 0, 0L, 0L, 0, 0L, 0L);
        return new PlanResult(List.of(), unassigned, total, metrics, explanations);
    }
}
