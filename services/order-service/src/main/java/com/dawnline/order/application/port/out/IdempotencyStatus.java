package com.dawnline.order.application.port.out;

/**
 * {@code idempotency_keys.status} (DESIGN.md §5.1 DDL).
 *
 * <p>order-service 는 {@link #DONE} 만 쓴다. {@link #IN_PROGRESS} 는 <em>읽기 경로에만</em>
 * 남아 있는 값이다 — 처리 중 표시는 30초 뒤 스스로 사라지는 Redis 키가 맡는다(ADR-018).
 * 커밋된 {@code IN_PROGRESS} 행은 프로세스가 죽으면 아무도 치우지 않고, 그 멱등 키로는
 * 다시 주문할 수 없게 된다.
 */
public enum IdempotencyStatus {

    /** 처리 중. 이 서비스는 쓰지 않지만, 읽으면 409 로 다룬다. */
    IN_PROGRESS,

    /** 완료. 저장된 응답을 재생할 수 있다. */
    DONE
}
