package com.dawnline.order.domain;

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
            case PLACED -> Set.of(PLANNED, CANCELLED);
            case PLANNED -> Set.of(DISPATCHED, CANCELLED);
            case DISPATCHED -> Set.of(DELIVERED, FAILED);
            // 종료 상태. 배송 실패의 재시도는 새 주문이지 이 주문의 전이가 아니다.
            case DELIVERED, FAILED, CANCELLED -> Set.of();
        };
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
