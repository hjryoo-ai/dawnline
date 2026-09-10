package com.dawnline.dispatch.domain;

import com.dawnline.common.error.ValidationException;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 이 계획을 어떤 모드로 돌릴지 정한다 (DESIGN.md §6.7 열화 모드, [ADR-034]).
 *
 * <h2>두 조건은 중복이 아니라 역할 분담이다</h2>
 * <ul>
 *   <li><strong>랙은 선행 지표다.</strong> 컷오프 직전 버스트(§8.2)는 "직전 계획은 빨랐는데
 *       웨이브가 갑자기 몰린" 순간이고, 예산 조건은 한 계획이 <em>이미 늦은 뒤에야</em> 켜진다.</li>
 *   <li><strong>랙이 진동을 막는다.</strong> 예산 조건만 있으면 FULL(느림) → FAST(빠름) →
 *       FULL(느림) 으로 번갈아 뛴다. 랙이 남아 있는 동안 FAST 를 유지시키는 것은 랙 조건이다.</li>
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
 * @param maxBacklogWaves 이 수를 <strong>넘으면</strong> 열화한다 (§6.7 기본 3 웨이브)
 * @param budgetRatio     직전 계획이 예산의 이 비율을 넘겼으면 열화한다 (§6.7 기본 0.8)
 */
public record PlanModeSelector(long maxBacklogWaves, double budgetRatio) {

    public PlanModeSelector {
        if (maxBacklogWaves < 0) {
            throw ValidationException.field("maxBacklogWaves", maxBacklogWaves,
                    "랙 임계는 음수일 수 없습니다");
        }
        if (!(budgetRatio > 0.0d) || budgetRatio > 1.0d) {
            throw ValidationException.field("budgetRatio", budgetRatio,
                    "예산 비율은 0 초과 1 이하여야 합니다");
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
            return new Decision(requested, PlanModeReason.REQUESTED);
        }
        if (backlog != null && backlog > maxBacklogWaves) {
            return new Decision(PlanMode.FAST, PlanModeReason.LAG);
        }
        if (lastPlanDuration != null && exceedsBudget(lastPlanDuration, budget)) {
            return new Decision(PlanMode.FAST, PlanModeReason.BUDGET);
        }
        if (backlog == null) {
            return new Decision(PlanMode.FULL, PlanModeReason.LAG_UNKNOWN);
        }
        return new Decision(PlanMode.FULL, PlanModeReason.NONE);
    }

    private boolean exceedsBudget(Duration last, Duration budget) {
        return last.toNanos() > budget.toNanos() * budgetRatio;
    }

    /**
     * 고른 모드와 그 근거.
     *
     * @param mode   실행 모드
     * @param reason 왜 그 모드인가 — 계획 행에 남고 메트릭 라벨이 된다
     */
    public record Decision(PlanMode mode, PlanModeReason reason) {

        public Decision {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
