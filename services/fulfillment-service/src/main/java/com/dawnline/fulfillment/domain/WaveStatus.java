package com.dawnline.fulfillment.domain;

import java.util.Objects;
import java.util.Set;

/**
 * 웨이브 상태 (DESIGN.md §5.2 Wave 수명주기).
 *
 * <pre>
 * OPEN ──(cutoff+grace 도달, 락 획득)──▶ CLOSING ──(wave.closed 발행 완료)──▶ CLOSED
 *                                                        ├──(plan.completed)──▶ PLANNED
 *                                                        └──(plan.failed)─────▶ PLAN_FAILED
 *                                                                                   │
 *                                                        (운영자 재실행 성공) ───────┘──▶ PLANNED
 * </pre>
 *
 * <h2>축 규칙은 마지막 두 전이에만 쓴다 (ADR-024 결정 4)</h2>
 * 앞의 세 상태({@code OPEN→CLOSING→CLOSED})는 <strong>이 서비스의 스케줄러가 스스로</strong>
 * 옮기므로 순서가 뒤바뀔 수 없다. 건너뜀은 순서 뒤바뀜이 아니라 버그이고, {@link #canTransitionTo}
 * 가 예외로 막는다.
 *
 * <p>마지막 두 전이는 다르다. {@code plan.completed} 와 {@code plan.failed} 는 <strong>서로 다른
 * 두 토픽</strong>에서 오고, 운영자 재실행이 있으면 1회차의 {@code plan.failed} 가 2회차의
 * {@code plan.completed} 보다 늦게 도착할 수 있다(§4.5 — 키가 같아도 토픽이 다르면 순서는
 * 보장되지 않는다). 그대로 두면 <em>라우트가 이미 나간 웨이브가 실패로 표시된다.</em>
 *
 * <pre>
 *   OPEN 0  →  CLOSING 1  →  CLOSED 2  →  PLAN_FAILED 3  →  PLANNED 4
 * </pre>
 *
 * {@code PLANNED} 가 축의 끝이자 <strong>흡수 상태</strong>다. 그 뒤에 온 {@code plan.failed} 는
 * {@link #hasProgressedPast} 가 참이라 무시하고 커밋한다
 * ({@code dawnline_event_rejected_total{reason="wave_already_planned"}}, §4.6 — DLQ 아님).
 * 이것은 순서를 봐주는 편법이 아니라 의미가 맞다 — 계획된 웨이브를 다시 돌려 실패해도 1회차의
 * 라우트는 여전히 유효하고, 그 웨이브는 계획된 웨이브다.
 *
 * <h2>마지막 두 전이는 Phase 3 까지 발화하지 않는다</h2>
 * {@code plan.completed}·{@code plan.failed} 의 발행자는 dispatch-service 이고 Phase 3 에 생긴다.
 * 계약은 소비자인 이쪽이 Phase 2 에 먼저 정의했으므로(ADR-024 결정 5) 리스너와 통합 테스트는
 * 예시 이벤트로 완결되지만, {@code make demo} 에서 실제로 발화하는 것은 Phase 3 부터다.
 */
public enum WaveStatus {

    /** 주문을 받는다. */
    OPEN,

    /** 컷오프에 도달해 마감 중. {@code wave.closed} 를 outbox 에 넣는 구간이다. */
    CLOSING,

    /** 마감됐다. 더는 주문을 받지 않는다. */
    CLOSED,

    /** 계획이 끝나 라우트가 생겼다 ({@code plan.completed}, Phase 3). 축의 끝이자 흡수 상태다. */
    PLANNED,

    /**
     * 계획이 실패했다 ({@code plan.failed}, Phase 3).
     *
     * <p><strong>종결 상태가 아니다.</strong> §5.3 이 "운영자 재실행 가능" 이라고 적은 경로가
     * 성공하면 {@code plan.completed} 가 다시 나오고 여기서 {@code PLANNED} 로 간다 (ADR-024 결정 3).
     */
    PLAN_FAILED;

    /** 이 상태에서 갈 수 있는 다음 상태들. */
    public Set<WaveStatus> allowedTransitions() {
        return switch (this) {
            case OPEN -> Set.of(CLOSING);
            case CLOSING -> Set.of(CLOSED);
            case CLOSED -> Set.of(PLANNED, PLAN_FAILED);
            // 운영자 재실행이 성공하면 돌아온다 (§5.3, ADR-024 결정 3).
            case PLAN_FAILED -> Set.of(PLANNED);
            case PLANNED -> Set.of();
        };
    }

    /**
     * {@code next} 로 전이할 수 있는가.
     *
     * <p>같은 상태로의 전이는 허용하지 않는다. 컷오프 스케줄러가 이미 {@code CLOSING} 인 웨이브를
     * 다시 잡는 일은 Redis 락({@code lock:wave:{id}})과 상태 조회가 막고, 그래도 여기까지 왔다면
     * 그것은 락이 새고 있다는 뜻이라 조용히 통과시키면 안 된다.
     *
     * @param next 목표 상태
     */
    public boolean canTransitionTo(WaveStatus next) {
        return allowedTransitions().contains(next);
    }

    /**
     * 계획 진행 단계 (ADR-024 결정 4).
     *
     * <p>{@code PLAN_FAILED}(3)가 {@code PLANNED}(4)보다 <em>앞</em> 인 이유는 재실행이 실패에서
     * 성공으로 가는 방향이기 때문이다. 그 반대는 없다.
     */
    public int progress() {
        return switch (this) {
            case OPEN -> 0;
            case CLOSING -> 1;
            case CLOSED -> 2;
            case PLAN_FAILED -> 3;
            case PLANNED -> 4;
        };
    }

    /**
     * {@code target} 이 <strong>이미 지나온 지점</strong>인가 (ADR-017 축 규칙, ADR-024 결정 4).
     *
     * <p><strong>계획 결과 두 리스너에서만 쓴다.</strong> {@code plan.completed}/{@code plan.failed}
     * 는 서로 다른 토픽이라 재실행 시 순서가 뒤바뀔 수 있고, 참이면 그 이벤트는 철 지난 것이므로
     * 무시하고 커밋한다. 앞의 세 상태를 옮기는 자기 스케줄러는 이 판정을 쓰지 않는다 — 거기서의
     * 건너뜀은 순서 뒤바뀜이 아니라 버그다.
     *
     * @param target 이벤트가 요구하는 상태
     */
    public boolean hasProgressedPast(WaveStatus target) {
        Objects.requireNonNull(target, "target");
        return target.progress() <= progress();
    }

    /** 새 주문을 받을 수 있는가. */
    public boolean acceptsOrders() {
        return this == OPEN;
    }

    /**
     * 마감이 이미 발행된 뒤인가.
     *
     * <p>이 시점 이후의 취소는 {@code waves.order_count} 를 건드리지 않는다 —
     * {@code wave.closed} 가 이미 그 숫자로 나갔기 때문이다 (ADR-022).
     */
    public boolean isClosedOrBeyond() {
        return this != OPEN && this != CLOSING;
    }

    /**
     * 하류가 계획을 끝냈는가. {@code fulfillment_orders} 정리 대상 판정에 쓴다 (ADR-023).
     *
     * <p>{@code PLAN_FAILED} 가 재실행으로 되살아날 수 있는데도 정리 대상에 넣는 이유는 시간
     * 축이 다르기 때문이다 — 재실행은 운영자가 실패를 보고 다시 돌리는 분~시간 단위이고, 정리는
     * 30·90일 뒤다. 삭제가 재실행을 앞지르는 경우가 없다 (ADR-024 결정 3).
     */
    public boolean isPlanningSettled() {
        return this == PLANNED || this == PLAN_FAILED;
    }

    /**
     * 더 이상 전이가 없는 종료 상태인가.
     *
     * <p>{@code PLANNED} 뿐이다. {@code PLAN_FAILED} 는 운영자 재실행으로 되살아난다 (ADR-024 결정 3).
     */
    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}
