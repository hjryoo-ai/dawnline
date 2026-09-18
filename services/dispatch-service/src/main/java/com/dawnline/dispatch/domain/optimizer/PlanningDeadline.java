package com.dawnline.dispatch.domain.optimizer;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 계획 <strong>전체</strong>의 마감 (DESIGN.md §6.7, [ADR-036]).
 *
 * <h2>예산은 한 단계의 것이 아니었다</h2>
 * §6.7 은 {@code PlanningBudget(totalMs, …)} 를 <em>계획</em>의 예산으로 썼는데, 구현은
 * {@link com.dawnline.dispatch.domain.optimizer.strategy.LocalSearchImprover 개선 단계}에만
 * 걸어 두었다. 그래서 `overload`(15,000 주문 / 60 차량)에서 개선 단계는 받은 13,841 ms 중
 * 12,859 ms 를 쓰며 예산을 <strong>정확히 지켰는데 계획은 43.2초</strong>가 걸렸다 —
 * 시간의 61%가 마감이 없는 재삽입에 있었다.
 *
 * <p>그 상태로는 <strong>열화 사다리(§6.7, [ADR-034])가 허구다.</strong> FAST 가 개선 단계를
 * 통째로 꺼도 43.2초는 30.4초가 될 뿐 30초가 되지 않는다. [ADR-028] 의 「재삽입은 값싼 탐욕이라
 * FAST 에서도 돈다」는 <em>실행 가능한 문제에서만</em> 참이었고, 그것을 참으로 만드는 것이
 * 이 마감이다.
 *
 * <h2>마감이 오면 남은 것은 미배정으로 끝낸다</h2>
 * 되돌리거나 예외를 던지지 않는다 — 그때까지 만든 계획은 유효하고, 하지 못한 일은
 * <strong>사유가 있는 미배정</strong>({@code plan-deadline})이 된다. 운영자의 "왜 이 주문이
 * 빠졌나"(§6.3)에 "시간이 없어 시도하지 못했습니다" 는 정직한 답이고, "실을 차가 없습니다" 는
 * 거짓말이다.
 *
 * <h2>결정성</h2>
 * 마감은 벽시계라 <strong>물리는 순간 결과가 기계에 달린다.</strong> 그래서 실현 가능한
 * 데이터셋에서는 마감이 물지 않아야 하고, 그 사실을 {@link PlanResult#budgetExhausted()} 로
 * 내보내 테스트·게이트·리포트가 <em>전제 어설션</em>으로 먼저 말한다 —
 * 「이 실행은 수렴으로 끝났다」([ADR-035] 4번, §6.9 재현 조건).
 *
 * <p>{@link #expired()} 는 <strong>판단 지점에서만</strong> 부른다. 그래서 {@link #hit()} 는
 * "시각이 지났다" 가 아니라 <strong>"마감 때문에 하지 않은 일이 있다"</strong> 를 뜻한다 —
 * 마지막 stop 을 넣고 정확히 마감에 닿은 계획은 잘린 것이 아니다.
 */
public final class PlanningDeadline {

    private final LongSupplier nanoTime;
    private final long startedNanos;
    private final long deadlineNanos;
    private boolean hit;

    /**
     * 지금부터 이 예산만큼.
     *
     * <p>{@code System.nanoTime()} 은 시각이 아니라 <strong>스톱워치</strong>다 — 불변규칙 12
     * 가 금지하는 것은 «지금이 몇 시인가» 를 묻는 일이고, 계획 시각은
     * {@link PlanningProblem#startedAt()} 이 이미 입력으로 들고 있다.
     *
     * @param budget 계획 예산 (§6.7)
     */
    public static PlanningDeadline from(PlanningBudget budget) {
        return new PlanningDeadline(System::nanoTime,
                Objects.requireNonNull(budget, "budget").total());
    }

    /**
     * @param nanoTime 경과 시간 원천. 테스트가 마감을 결정적으로 만들 때 바꾼다
     * @param total    계획 전체에 쓸 수 있는 시간
     */
    public PlanningDeadline(LongSupplier nanoTime, Duration total) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        Objects.requireNonNull(total, "total");
        this.startedNanos = nanoTime.getAsLong();
        this.deadlineNanos = startedNanos + total.toNanos();
    }

    /**
     * 마감이 지났는가 — <strong>판단 지점에서만</strong> 부른다.
     *
     * <p>한 번 «예» 면 계속 «예» 다. 시간은 되돌아가지 않으므로 값이 달라지지 않고,
     * 잘린 뒤의 루프가 시계를 다시 읽지 않아도 된다.
     */
    public boolean expired() {
        if (hit) {
            return true;
        }
        hit = nanoTime.getAsLong() >= deadlineNanos;
        return hit;
    }

    /**
     * 마감 때문에 하지 <strong>못한 일이 있는가</strong>.
     *
     * <p>시계를 읽지 않는다 — {@link #expired()} 가 판단 지점에서 «예» 를 낸 적이 있는지만
     * 본다. 그래서 이 값이 «아니오» 면 그 계획은 <strong>수렴으로 끝났고 재현 가능하다.</strong>
     */
    public boolean hit() {
        return hit;
    }

    /** 시작부터 지금까지. 개선 예산(§6.7 사다리)이 「앞 단계가 쓴 시간」으로 쓴다. */
    public long elapsedNanos() {
        return nanoTime.getAsLong() - startedNanos;
    }
}
