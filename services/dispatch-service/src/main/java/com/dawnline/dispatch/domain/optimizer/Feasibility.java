package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.error.ValidationException;
import java.util.Objects;

/**
 * 하드 룰 판정 결과 (DESIGN.md §6.3).
 *
 * <p>불가 판정은 <strong>사유를 반드시 들고 있다</strong> — 이 값이 그대로 {@link Explanation} 이
 * 되어 운영자의 "왜 이 주문이 미배정인가" 에 답하기 때문이다. 사유 없는 거절은 §6.3 이 룰을
 * 데이터로 둔 이유를 무너뜨린다.
 *
 * <h2>위반 폭 — 같은 룰끼리만 견준다</h2>
 * {@code excess} 는 이 거절이 한도를 <strong>얼마나</strong> 넘었는지를 <em>그 룰의 단위</em>로 말한다(지각 · 근무창은 넘긴 분, 용량은
 * 한도 대비 ‰). 미배정 설명이 여러 라우트의 거절 가운데 통과에 가장 가까웠던 것을 고를 때 쓴다({@link RuleSet#closestFirst()},
 * ADR-039 후속). 단위가 룰마다 다르므로 <strong>다른 룰의 폭끼리는 견주지 않는다</strong> — 그 순서는 평가 순서가 정한다. 폭을
 * 말할 수 없는 룰(속성 불일치 · stop 상한)은 0 이다.
 *
 * @param feasible 실을 수 있는가
 * @param ruleName 위반한 룰 이름. 통과면 {@code null}
 * @param reason   사람이 읽을 사유. 통과면 {@code null}
 * @param excess   위반 폭(그 룰의 단위, 0 이상). 통과면 0
 */
public record Feasibility(boolean feasible, String ruleName, String reason, long excess) {

    private static final Feasibility OK = new Feasibility(true, null, null, 0L);

    public Feasibility {
        if (feasible && (ruleName != null || reason != null || excess != 0L)) {
            throw new ValidationException("통과 판정에는 위반 사유가 없어야 합니다",
                    java.util.Map.of("ruleName", String.valueOf(ruleName)));
        }
        if (!feasible) {
            Objects.requireNonNull(ruleName, "ruleName");
            Objects.requireNonNull(reason, "reason");
        }
        if (excess < 0L) {
            throw ValidationException.field("excess", excess, "위반 폭은 음수일 수 없습니다");
        }
    }

    /** 폭을 말하지 않는 판정. */
    public Feasibility(boolean feasible, String ruleName, String reason) {
        this(feasible, ruleName, reason, 0L);
    }

    /** 통과. */
    public static Feasibility ok() {
        return OK;
    }

    /**
     * 불가.
     *
     * @param ruleName 위반한 룰 이름
     * @param reason   사유
     */
    public static Feasibility violated(String ruleName, String reason) {
        return new Feasibility(false, ruleName, reason, 0L);
    }

    /**
     * 불가 — 한도를 넘은 폭과 함께.
     *
     * @param ruleName 위반한 룰 이름
     * @param reason   사유
     * @param excess   위반 폭(그 룰의 단위, 0 이상)
     */
    public static Feasibility violated(String ruleName, String reason, long excess) {
        return new Feasibility(false, ruleName, reason, excess);
    }
}
