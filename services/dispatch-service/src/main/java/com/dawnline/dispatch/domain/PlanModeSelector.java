package com.dawnline.dispatch.domain;

import com.dawnline.common.error.ValidationException;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 이 계획을 어떤 모드로 돌릴지 정한다 (DESIGN.md §6.7 열화 모드, [ADR-034]).
 *
 * <h2>열화는 사다리다 — 두 조건은 다른 것을 말하고, 처방도 다르다</h2>
 * <table>
 *   <caption>사다리의 두 단</caption>
 *   <tr><th>조건</th><th>무엇을 뜻하는가</th><th>처방</th></tr>
 *   <tr><td>직전 계획 &gt; 예산 × 비율</td><td><strong>개선 단계가 예산을 다 썼다</strong></td>
 *       <td>다음 계획의 <em>개선 예산을 줄인다</em> (FULL 유지)</td></tr>
 *   <tr><td>파티션 랙 &gt; 임계</td><td><strong>처리량이 모자란다</strong></td>
 *       <td>개선 단계를 <em>끈다</em> (FAST)</td></tr>
 * </table>
 *
 * <p><strong>2026-09-10 정정.</strong> 처음에는 두 조건이 같은 처방(FAST)을 냈다. 그것이 틀린
 * 이유는 측정이 보여 줬다 — 예산 5초에서 개선 단계를 예산으로 끊은 대가는 <strong>+0.21%</strong>
 * 인데 FAST 로 보내는 대가는 <strong>+9.4%</strong> 다. <em>45배 비싼 처방</em>이었다.
 * §6.7 의 예산 조건은 ADR-032 의 패스 단위 예산이 생기기 <em>전에</em>
 * 쓰인 문장이고, 예산이 이미 계획 시간을 상한으로 묶는 지금 "직전 계획이 예산에 가까웠다" 는
 * 과부하 신호가 아니라 <strong>"개선 단계가 예산을 다 썼다"</strong>일 뿐이다.
 * <strong>처리량 부족을 말하는 유일한 신호는 랙이다.</strong>
 *
 * <h2>랙은 여전히 선행 지표이고, 진동을 막는 것도 랙이다</h2>
 * <ul>
 *   <li><strong>랙은 선행 지표다.</strong> 컷오프 직전 버스트(§8.2)는 "직전 계획은 빨랐는데
 *       웨이브가 갑자기 몰린" 순간이고, 예산 조건은 한 계획이 <em>이미 늦은 뒤에야</em> 켜진다.</li>
 *   <li><strong>랙이 FAST 를 유지시킨다.</strong> 랙이 남아 있는 동안 계속 FAST 다 — 그래서
 *       FAST 단에는 진동이 없다. 예산 단(FULL, 계수 축소)은 한 번 내려갔다가 조건이 사라지면
 *       올라오므로 번갈아 뛰지만, <strong>그 진폭이 +9.4% 가 아니라 1% 대</strong>라서 그것이
 *       바로 사다리로 고친 것이다.</li>
 * </ul>
 *
 * <h2>모름을 0으로 접지 않는다</h2>
 * {@code backlog} 가 {@code null} 이면 그것은 "랙 0" 이 아니라 <strong>모름</strong>이다
 * (리밸런스 직후, 운영자 재실행, 정체 회수 — 이벤트가 없는 경로에는 파티션이 없다). 접으면 랙
 * 조건이 조용히 「아니오」가 되어, 판단이 멈춘 것과 정상이 구별되지 않는다.
 *
 * <h2>열화는 래치가 아니다</h2>
 * 상태를 들지 않는다 — 매 계획마다 두 사실을 다시 본다. 밀리는 동안 FAST 로 있다가 여유가
 * 생기면 다음 계획이 곧바로 FULL 이다. "한 번 열화하면 누가 되돌리는가" 라는 질문이 생기지 않는다.
 *
 * @param maxBacklogWaves 이 수를 <strong>넘으면</strong> FAST 다 (§6.7 기본 3 웨이브)
 * @param budgetRatio     직전 계획이 예산의 이 비율을 넘겼으면 개선 예산을 줄인다 (기본 0.8)
 * @param budgetFactor    그때 개선 예산에 곱하는 계수 (기본 0.5 — 절반)
 */
public record PlanModeSelector(long maxBacklogWaves, double budgetRatio, double budgetFactor) {

    /** 계수를 줄이지 않는 정상 값. */
    public static final double FULL_BUDGET = 1.0d;

    public PlanModeSelector {
        if (maxBacklogWaves < 0) {
            throw ValidationException.field("maxBacklogWaves", maxBacklogWaves,
                    "랙 임계는 음수일 수 없습니다");
        }
        if (!(budgetRatio > 0.0d) || budgetRatio > 1.0d) {
            throw ValidationException.field("budgetRatio", budgetRatio,
                    "예산 비율은 0 초과 1 이하여야 합니다");
        }
        if (!(budgetFactor > 0.0d) || budgetFactor > 1.0d) {
            throw ValidationException.field("budgetFactor", budgetFactor,
                    "개선 예산 계수는 0 초과 1 이하여야 합니다");
        }
    }

    /**
     * 모드를 고른다.
     *
     * @param requested        운영자가 지정한 모드. {@code null} 이면 자동 판단
     * @param backlog          이 계획을 부른 파티션의 컨슈머 랙(레코드 수). <strong>{@code null}
     *                         은 0 이 아니라 모름</strong>이다
     * @param lastPlanDuration 같은 캠프의 마지막 발행 계획이 알고리즘에 쓴 시간.
     *                         {@code null} 이면 없다
     * @param budget           계획 전체의 시간 예산 (§6.7)
     */
    public Decision select(@Nullable PlanMode requested, @Nullable Long backlog,
            @Nullable Duration lastPlanDuration, Duration budget) {

        Objects.requireNonNull(budget, "budget");
        if (requested != null) {
            // 사람이 고른 것은 열화가 아니다. 자동 판단은 아예 돌지 않는다 — 돌면 "운영자가
            // FULL 을 지정했는데 FAST 로 돌았다" 가 생긴다.
            return new Decision(requested, PlanModeReason.REQUESTED, FULL_BUDGET);
        }
        if (backlog != null && backlog > maxBacklogWaves) {
            // 사다리의 윗단 — 처리량이 모자란다. 개선을 <strong>끈다</strong>.
            return new Decision(PlanMode.FAST, PlanModeReason.LAG, FULL_BUDGET);
        }
        if (lastPlanDuration != null && exceedsBudget(lastPlanDuration, budget)) {
            // 사다리의 아랫단 — 개선 단계가 예산을 다 썼을 뿐이다. 개선을 <strong>덜 한다</strong>.
            // 모드는 FULL 로 남는다: 계약(plan.completed.mode)의 값은 둘뿐이고, 이 단은 "무엇을
            // 생략했나" 가 아니라 "얼마나 했나" 라서 mode 가 아니라 사유가 말할 일이다.
            return new Decision(PlanMode.FULL, PlanModeReason.BUDGET, budgetFactor);
        }
        if (backlog == null) {
            return new Decision(PlanMode.FULL, PlanModeReason.LAG_UNKNOWN, FULL_BUDGET);
        }
        return new Decision(PlanMode.FULL, PlanModeReason.NONE, FULL_BUDGET);
    }

    private boolean exceedsBudget(Duration last, Duration budget) {
        return last.toNanos() > budget.toNanos() * budgetRatio;
    }

    /**
     * 고른 모드와 그 근거.
     *
     * @param mode         실행 모드
     * @param reason       왜 그 모드인가 — 계획 행에 남고 메트릭 라벨이 된다
     * @param budgetFactor 개선 예산에 곱할 계수. {@code BUDGET} 단에서만 1 보다 작다
     */
    public record Decision(PlanMode mode, PlanModeReason reason, double budgetFactor) {

        public Decision {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
