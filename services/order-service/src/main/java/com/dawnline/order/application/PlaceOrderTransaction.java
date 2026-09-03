package com.dawnline.order.application;

import com.dawnline.common.error.ConflictException;
import com.dawnline.order.application.port.in.OrderAccepted;
import com.dawnline.order.application.port.out.IdempotencyClaim;
import com.dawnline.order.application.port.out.IdempotencyRecords;
import com.dawnline.order.application.port.out.OrderEvents;
import com.dawnline.order.application.port.out.OrderRepository;
import com.dawnline.order.domain.Order;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 접수의 트랜잭션 경계 (DESIGN.md §5.1 3단계, CLAUDE.md 불변규칙 1).
 *
 * <p>이 한 트랜잭션이 네 가지를 함께 커밋한다: {@code orders} · {@code order_items} ·
 * {@code outbox_events} · {@code idempotency_keys(DONE)}. 넷 중 하나라도 실패하면 넷 다 없던 일이 된다.
 * "주문은 저장됐는데 이벤트가 없는" 상태가 불가능한 이유가 이것이다.
 *
 * <h2>왜 {@link PlaceOrderService} 와 따로 있는가</h2>
 * {@code @Transactional} 은 프록시가 가로채야 동작한다. 같은 빈 안에서 부르면(self-invocation)
 * 프록시를 지나가지 않아 <strong>트랜잭션이 조용히 없는 채로</strong> 실행된다 — 컴파일도 되고
 * 테스트도 통과하는데 outbox 와 주문이 서로 다른 트랜잭션이 된다. 별개 빈으로 두면 그 함정이 없다.
 *
 * <p>지오코딩·티어 판정·약속창 계산은 여기 없다. 그 셋은 실패해도 되돌릴 것이 없는 순수 계산이라
 * 트랜잭션 밖에서 끝낸다 — 잘못된 우편번호 하나가 DB 커넥션을 열 이유가 없다(§5.1 "쓰기 경로를 최소화").
 */
public class PlaceOrderTransaction {

    /**
     * {@code idempotency_keys.response_code} 에 저장하는 값 (§5.1 DDL).
     *
     * <p>{@code application} 이 HTTP 를 아는 유일한 지점이다. 컬럼이 HTTP 상태 코드를 담도록
     * 설계돼 있어서 그렇고, 재생 시 그 코드를 그대로 쓰는 것이 "그때 준 답" 의 일부이기 때문이다.
     */
    private static final int CREATED = 201;

    private final OrderRepository orders;
    private final OrderEvents events;
    private final IdempotencyRecords records;

    /**
     * @param orders  주문 저장소
     * @param events  이벤트 발행 포트 (outbox)
     * @param records 멱등 기록 저장소
     */
    public PlaceOrderTransaction(OrderRepository orders, OrderEvents events, IdempotencyRecords records) {
        this.orders = Objects.requireNonNull(orders, "orders");
        this.events = Objects.requireNonNull(events, "events");
        this.records = Objects.requireNonNull(records, "records");
    }

    /**
     * 주문·이벤트·멱등 기록을 한 트랜잭션으로 쓴다.
     *
     * @param order 접수할 주문
     * @param claim 멱등 키·지문·보관 기간
     * @return 저장된 응답
     * @throws ConflictException 그 사이 다른 요청이 같은 멱등 키를 끝냈을 때 (트랜잭션은 롤백된다)
     */
    @Transactional
    public OrderAccepted commit(Order order, IdempotencyClaim claim) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(claim, "claim");

        orders.save(order);
        events.placed(order);

        OrderAccepted accepted = OrderAccepted.of(order);
        if (!records.complete(claim, CREATED, accepted)) {
            // 업서트가 0행이다 = 그 키는 이미 DONE 이다. 여기서 던져야 방금 넣은 주문·이벤트가 함께 사라진다.
            throw new ConflictException("같은 멱등 키의 요청이 이미 완료되었습니다",
                    Map.of("idempotencyKey", claim.key()));
        }
        return accepted;
    }
}
