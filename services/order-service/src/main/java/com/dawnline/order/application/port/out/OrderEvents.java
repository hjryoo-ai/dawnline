package com.dawnline.order.application.port.out;

import com.dawnline.order.domain.Order;

/**
 * 주문 이벤트 발행 포트 (DESIGN.md §4.3, CLAUDE.md 불변규칙 1).
 *
 * <p>구현은 {@code adapter.out.messaging} 이며 outbox 에 행 하나를 INSERT 한다. 그 INSERT 가
 * 호출한 유스케이스의 트랜잭션에 그대로 참여하므로, 도메인 변경이 롤백되면 이벤트도 사라진다.
 *
 * <p>포트를 둔 이유: <strong>이벤트 페이로드의 모양은 서비스 간 계약</strong>이고
 * (contracts/events/*.schema.json), 계약을 아는 것은 어댑터의 책임이다. 유스케이스는
 * "주문이 접수됐다" 는 사실만 말한다.
 */
public interface OrderEvents {

    /**
     * {@code order.placed} 를 outbox 에 기록한다. <strong>주문 트랜잭션 안에서</strong> 호출한다.
     *
     * @param order 접수된 주문
     */
    void placed(Order order);
}
