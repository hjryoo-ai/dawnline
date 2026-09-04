package com.dawnline.order.application.port.out;

import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderStatus;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

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

    /**
     * {@code order.cancelled} 를 outbox 에 기록한다. <strong>주문 트랜잭션 안에서</strong> 호출한다.
     *
     * <p>{@code previousStatus} 를 인자로 받는 이유: 이 메서드가 불릴 때 애그리거트는 이미
     * {@code CANCELLED} 다. 소비자는 <em>무엇에서</em> 취소됐는지를 알아야 한다 —
     * {@code PLACED} 취소는 아직 아무 웨이브에도 안 들어간 것이고, {@code PLANNED} 취소는
     * 이미 편성된 웨이브에서 빼야 하는 것이라 할 일이 다르다(계약 스키마 참고).
     *
     * @param order          취소된 주문
     * @param previousStatus 취소 직전 상태
     * @param reason         취소 사유. 없을 수 있다
     */
    void cancelled(Order order, OrderStatus previousStatus, @Nullable String reason);
}
