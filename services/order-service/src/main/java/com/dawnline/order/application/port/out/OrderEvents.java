package com.dawnline.order.application.port.out;

import com.dawnline.order.domain.Order;
import java.time.Instant;

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
     * <p>{@code cutoffAt} 이 애그리거트가 아니라 인자로 오는 이유: 이 값은 {@code orders} 에 저장하지
     * 않는다. §5.1 DDL 에 없는 컬럼이고, 접수 이후 order-service 가 그 값을 쓰는 곳이 없다.
     * 필요한 쪽은 fulfillment-service 이고(§5.2 웨이브 키), 그쪽으로 가는 통로가 이 이벤트다.
     * 계산은 {@code DeliveryPromise} 한 곳에서만 한다 — 같은 계산이 두 서비스에 있으면 §2.2 표를
     * 한쪽만 고치는 날 약속과 웨이브가 말없이 어긋난다.
     *
     * @param order    접수된 주문
     * @param cutoffAt 이 주문이 실릴 웨이브의 컷오프 (§2.2)
     */
    void placed(Order order, Instant cutoffAt);
}
