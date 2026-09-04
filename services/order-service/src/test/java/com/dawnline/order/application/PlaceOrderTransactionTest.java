package com.dawnline.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.error.ConflictException;
import com.dawnline.order.application.port.in.OrderAccepted;
import com.dawnline.order.application.port.out.IdempotencyClaim;
import com.dawnline.order.application.port.out.IdempotencyRecords;
import com.dawnline.order.application.port.out.OrderEvents;
import com.dawnline.order.application.port.out.OrderRepository;
import com.dawnline.order.domain.DeliveryAddress;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.Parcel;
import com.dawnline.order.domain.PromisedWindow;
import com.dawnline.order.domain.ServiceTier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 접수 트랜잭션 (DESIGN.md §5.1 3단계, 불변규칙 1).
 *
 * <p>실제 커밋·롤백은 통합 테스트가 본다. 여기서 보는 것은 <em>이 메서드 하나가 세 가지를 모두
 * 부르는가</em>와 <em>업서트가 0행일 때 던지는가</em>다. 후자가 롤백의 방아쇠이므로, 조용히
 * {@code false} 를 무시하면 같은 주문이 두 번 저장된다.
 */
@DisplayName("PlaceOrderTransaction — 주문·이벤트·멱등 기록을 함께 쓴다")
class PlaceOrderTransactionTest {

    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

    private OrderRepository orders;
    private OrderEvents events;
    private IdempotencyRecords records;
    private PlaceOrderTransaction transaction;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        events = mock(OrderEvents.class);
        records = mock(IdempotencyRecords.class);
        when(records.complete(any(), anyInt(), any())).thenReturn(true);
        transaction = new PlaceOrderTransaction(orders, events, records);
    }

    private static Order order() {
        return Order.place(Ids.newId(), Ids.newId(), ServiceTier.DAWN,
                DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GeoPoint.of(37.4979, 127.0276)),
                PromisedWindow.of(NOW.plus(Duration.ofHours(14)), NOW.plus(Duration.ofHours(21)),
                        ServiceTier.DAWN),
                new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1", 1)), NOW);
    }

    /** §2.2 DAWN 컷오프. 저장되지 않고 이벤트로만 나간다. */
    private static final Instant CUTOFF_AT = NOW.plus(Duration.ofHours(14));

    private static IdempotencyClaim claim() {
        return new IdempotencyClaim("idem-1", "a".repeat(64), NOW, NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void 주문_이벤트_멱등기록을_그_순서로_쓴다() {
        Order order = order();

        OrderAccepted accepted = transaction.commit(order, CUTOFF_AT, claim());

        InOrder calls = inOrder(orders, events, records);
        calls.verify(orders).save(order);
        calls.verify(events).placed(order, CUTOFF_AT);
        calls.verify(records).complete(claim(), 201, accepted);
    }

    @Test
    void 반환값은_저장한_주문에서_만든_응답이다() {
        Order order = order();

        OrderAccepted accepted = transaction.commit(order, CUTOFF_AT, claim());

        assertThat(accepted).isEqualTo(OrderAccepted.of(order));
        assertThat(accepted.orderId()).isEqualTo(order.id());
        assertThat(accepted.placedAt()).isEqualTo(NOW);
    }

    @Test
    void 업서트가_0행이면_던진다() {
        // 던져야 방금 넣은 주문·이벤트가 함께 롤백된다. false 를 무시하면 같은 멱등 키로
        // 주문이 두 건 남는다 — 멱등의 실패이자 되돌리기 어려운 상태다.
        when(records.complete(any(), anyInt(), any())).thenReturn(false);

        assertThatThrownBy(() -> transaction.commit(order(), CUTOFF_AT, claim()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("이미 완료");
    }

    @Test
    void commit_에_Transactional_이_붙어_있다() throws NoSuchMethodException {
        // ArchUnit 규칙 5 는 어노테이션의 <위치>만 본다 — 없어진 것은 잡지 못한다.
        // 이 클래스가 존재하는 이유가 그 어노테이션이므로 여기서 확인한다.
        assertThat(PlaceOrderTransaction.class
                .getMethod("commit", Order.class, Instant.class, IdempotencyClaim.class)
                .isAnnotationPresent(Transactional.class))
                .as("@Transactional 이 없으면 주문·outbox·멱등 기록이 서로 다른 트랜잭션이 된다")
                .isTrue();
    }

    @Test
    void null_인자는_거부한다() {
        assertThatThrownBy(() -> transaction.commit(null, CUTOFF_AT, claim()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> transaction.commit(order(), null, claim()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> transaction.commit(order(), CUTOFF_AT, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlaceOrderTransaction(null, events, records))
                .isInstanceOf(NullPointerException.class);
    }
}
