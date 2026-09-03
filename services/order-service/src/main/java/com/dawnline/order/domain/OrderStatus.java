package com.dawnline.order.domain;

import java.util.Objects;
import java.util.Set;

/**
 * 주문 상태 (DESIGN.md §5.1 상태 머신).
 *
 * <pre>
 * PLACED ──(fulfillment.planned)──▶ PLANNED ──(order.dispatched)──▶ DISPATCHED ──(delivery COMPLETED)──▶ DELIVERED
 *   │                                  │                                 └──(delivery FAILED)──▶ FAILED
 *   └──────── cancel ──────────────────┴──▶ CANCELLED     (DISPATCHED 이후 취소 불가 → 409)
 * </pre>
 *
 * <p>전이 규칙을 <strong>상태 자신이</strong> 안다. {@link Order} 의 각 전이 메서드가
 * {@code if (status != X)} 를 나열하면 표가 코드 여기저기로 흩어지고, 상태가 하나 늘 때
 * 어디를 고쳐야 하는지가 사라진다. 여기에 모아 두면 전이표가 한 곳에 있고
 * {@code OrderStatusTest} 가 그 표 전체를 훑을 수 있다 (CLAUDE.md 불변규칙 6).
 */
public enum OrderStatus {

    /** 접수됨. 아직 어느 웨이브에도 들어가지 않았다. */
    PLACED,

    /** FC·캠프·권역·웨이브가 정해졌다 ({@code fulfillment.planned} 수신). */
    PLANNED,

    /** 라우트에 배정돼 배송이 시작됐다 ({@code order.dispatched} 수신). 이후 취소 불가. */
    DISPATCHED,

    /** 배송 완료. */
    DELIVERED,

    /** 배송 실패. */
    FAILED,

    /** 취소됨. */
    CANCELLED;

    /** 이 상태에서 갈 수 있는 다음 상태들. */
    public Set<OrderStatus> allowedTransitions() {
        return switch (this) {
            // 진행 축에서 <앞으로> 가는 전이는 전부 허용한다. 건너뜀은 오류가 아니라 순서 뒤바뀜이다 —
            // 사실은 이미 일어났고, 순서가 다른 것은 우리가 알게 된 순서일 뿐이다 (ADR-017).
            //
            // order-service 가 소비하는 세 이벤트는 서로 다른 토픽이라 셋 사이의 순서가 보장되지
            // 않는다(§4.5): fulfillment.planned(키 orderId) · order.dispatched(키 orderId) ·
            // delivery.status(키 routeId). 그래서 이런 일이 모두 실제로 가능하다.
            //   - PLANNED 인데 delivery.status(COMPLETED) 가 먼저   → PLANNED → DELIVERED
            //   - PLACED  인데 order.dispatched 가 먼저             → PLACED  → DISPATCHED
            //   - PLACED  인데 delivery.status 가 먼저              → PLACED  → DELIVERED
            // 억지 보정이 아니라 사실을 반영한 것이다 — 배송이 완료됐다면 배송은 시작된 것이고,
            // 중간 상태를 안 거친 것은 그 사건을 알리는 메시지가 아직 안 왔다는 뜻일 뿐이다.
            case PLACED -> Set.of(PLANNED, DISPATCHED, DELIVERED, FAILED, CANCELLED);
            case PLANNED -> Set.of(DISPATCHED, DELIVERED, FAILED, CANCELLED);
            case DISPATCHED -> Set.of(DELIVERED, FAILED);
            // 종료 상태. 배송 실패의 재시도는 새 주문이지 이 주문의 전이가 아니다.
            case DELIVERED, FAILED, CANCELLED -> Set.of();
        };
    }

    /**
     * 배송 진행 단계 (ADR-017).
     *
     * <p>{@code PLACED(0) → PLANNED(1) → DISPATCHED(2) → DELIVERED·FAILED(3)}.
     * 리스너가 "이 이벤트가 철 지난 것인가" 를 판단하는 축이다.
     *
     * <p>{@code CANCELLED} 는 이 축 위에 없다({@code -1}). 취소된 주문에 배송 이벤트가 오는 것은
     * 철 지난 중복이 아니라 <strong>실제로 잘못된 상황</strong>이라(취소된 소포가 차에 실려 있다)
     * 조용히 버리지 않고 알림 가능한 메트릭으로 남겨야 하기 때문이다 (§4.6, §4.5).
     */
    public int progress() {
        return switch (this) {
            case PLACED -> 0;
            case PLANNED -> 1;
            case DISPATCHED -> 2;
            case DELIVERED, FAILED -> 3;
            case CANCELLED -> -1;
        };
    }

    /**
     * {@code target} 이 <strong>이미 지나온 지점</strong>인가 (ADR-017).
     *
     * <p>참이면 그 이벤트는 순서가 뒤바뀌어 늦게 도착한 것이므로 무시하고 커밋한다. 예: 주문이
     * 이미 {@code DELIVERED} 인데 {@code order.dispatched} 가 도착하면 {@code DISPATCHED}(2) 는
     * 현재(3)보다 뒤라 버린다.
     *
     * <p>둘 중 하나라도 진행 축 밖({@code CANCELLED})이면 거짓이다 — 비교할 축이 없다.
     *
     * @param target 이벤트가 요구하는 상태
     */
    public boolean hasProgressedPast(OrderStatus target) {
        Objects.requireNonNull(target, "target");
        if (progress() < 0 || target.progress() < 0) {
            return false;
        }
        return target.progress() <= progress();
    }

    /**
     * {@code next} 로 전이할 수 있는가.
     *
     * <p>같은 상태로의 전이는 허용하지 않는다. at-least-once 로 같은 이벤트가 두 번 와도
     * 여기까지 오지 않기 때문이다 — 중복은 {@code processed_events} 가 리스너 앞단에서 거른다
     * (불변규칙 2). 여기서 조용히 통과시키면 진짜 잘못된 전이까지 함께 숨는다.
     *
     * @param next 목표 상태
     */
    public boolean canTransitionTo(OrderStatus next) {
        return allowedTransitions().contains(next);
    }

    /** 더 이상 전이가 없는 종료 상태인가. */
    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }

    /** 고객이 취소할 수 있는 상태인가 (§5.1: {@code DISPATCHED} 이후 불가). */
    public boolean isCancellable() {
        return canTransitionTo(CANCELLED);
    }
}
