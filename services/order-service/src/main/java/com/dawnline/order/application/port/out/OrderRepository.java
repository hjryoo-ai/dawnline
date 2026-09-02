package com.dawnline.order.application.port.out;

import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code orders} 접근 포트 (DESIGN.md §5.1).
 *
 * <p>유일한 프로덕션 구현은 {@code adapter.out.persistence.JpaOrderRepository} 다.
 * 인터페이스로 둔 이유는 유스케이스를 DB 없이 단위 테스트하기 위해서다.
 */
public interface OrderRepository {

    /**
     * 새 주문을 저장한다. 이미 있는 id 면 예외다 — 멱등 재요청은 이 앞단
     * ({@code idempotency_keys})에서 걸러져야 한다.
     *
     * @param order 저장할 주문
     */
    void save(Order order);

    /**
     * 상태 전이를 반영한다. 낙관적 락 충돌은 구현이 던진다.
     *
     * @param order 변경된 주문
     */
    void update(Order order);

    /**
     * 주문 하나를 읽는다.
     *
     * @param id 주문 id
     */
    Optional<Order> findById(UUID id);

    /**
     * 고객의 주문 목록. 접수 시각 <strong>내림차순</strong>이며 커서 이후의 것만 돌려준다
     * (§5.1 {@code GET /api/v1/orders}).
     *
     * <p>커서를 {@code (placedAt, id)} 두 값으로 받는 이유: {@code placedAt} 만으로는 같은
     * 밀리초에 접수된 주문들 사이에서 커서가 멈추지 못해 건너뛰거나 반복된다. UUIDv7 은 시간순
     * 정렬이 되므로 두 번째 키로 쓰기에 알맞다(불변규칙 10).
     *
     * @param customerId  고객 id
     * @param status      상태 필터. {@code null} 이면 전체
     * @param from        접수 시각 하한(포함). {@code null} 이면 제한 없음
     * @param to          접수 시각 상한(제외). {@code null} 이면 제한 없음
     * @param cursorPlacedAt 이 시각보다 <em>이전</em>부터. {@code null} 이면 처음부터
     * @param cursorId    같은 시각일 때 이 id 보다 <em>작은</em> 것부터
     * @param limit       최대 건수
     */
    List<Order> findByCustomer(UUID customerId, @Nullable OrderStatus status,
            @Nullable Instant from, @Nullable Instant to,
            @Nullable Instant cursorPlacedAt, @Nullable UUID cursorId, int limit);
}
