package com.dawnline.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.order.domain.DeliveryAddress;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.Parcel;
import com.dawnline.order.domain.PromisedWindow;
import com.dawnline.order.domain.ServiceTier;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link JpaOrderRepository} 의 쿼리 조립과 방어 조건.
 *
 * <p>실행 결과는 {@code OrderPersistenceIT} 가 실물 PostgreSQL 로 본다. 여기서 보는 것은 <em>어떤
 * JPQL 이 만들어지는가</em> 다 — 조건이 없을 때 절이 붙지 않아야 플래너가 인덱스로 좁힐 수 있고,
 * 그 사실은 DB 를 띄우지 않고도 확인할 수 있다.
 */
@DisplayName("JpaOrderRepository — 동적 쿼리 조립")
class JpaOrderRepositoryTest {

    private static final Instant PLACED_AT = Instant.parse("2026-09-02T10:00:00Z");

    private EntityManager entityManager;
    private JpaOrderRepository repository;
    private ArgumentCaptor<String> jpql;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        entityManager = mock(EntityManager.class);
        repository = new JpaOrderRepository(entityManager);
        jpql = ArgumentCaptor.forClass(String.class);

        TypedQuery<OrderEntity> query = mock(TypedQuery.class);
        when(query.setMaxResults(org.mockito.ArgumentMatchers.anyInt())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        when(entityManager.createQuery(anyString(), eq(OrderEntity.class))).thenReturn(query);
    }

    private static Order order() {
        return Order.place(Ids.newId(), Ids.newId(), ServiceTier.DAWN,
                DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GeoPoint.of(37.4979, 127.0276)),
                PromisedWindow.of(PLACED_AT.plus(Duration.ofHours(14)),
                        PLACED_AT.plus(Duration.ofHours(21)), ServiceTier.DAWN),
                new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1", 1)), PLACED_AT);
    }

    private String capturedJpql() {
        verify(entityManager).createQuery(jpql.capture(), eq(OrderEntity.class));
        return jpql.getValue();
    }

    @Test
    void 필터가_없으면_고객_조건만_붙는다() {
        repository.findByCustomer(Ids.newId(), null, null, null, null, null, 20);

        String query = capturedJpql();
        assertThat(query).contains("o.customerId = :customerId");
        assertThat(query).doesNotContain(":status", ":from", ":to", ":cursorPlacedAt");
        assertThat(query).endsWith("ORDER BY o.placedAt DESC, o.id DESC");
    }

    @Test
    void 상태_필터가_있으면_그_절만_더_붙는다() {
        repository.findByCustomer(Ids.newId(), OrderStatus.CANCELLED, null, null, null, null, 20);

        String query = capturedJpql();
        assertThat(query).contains("o.status = :status");
        assertThat(query).doesNotContain(":from", ":to");
    }

    @Test
    void 기간_필터는_시작은_포함_끝은_제외다() {
        repository.findByCustomer(Ids.newId(), null, PLACED_AT, PLACED_AT.plusSeconds(60), null, null, 20);

        String query = capturedJpql();
        assertThat(query).contains("o.placedAt >= :from");
        assertThat(query).contains("o.placedAt < :to");
    }

    @Test
    void 커서는_시각과_id_두_값으로_비교한다() {
        repository.findByCustomer(Ids.newId(), null, null, null, PLACED_AT, Ids.newId(), 20);

        // 시각만 비교하면 같은 밀리초의 주문들에서 건너뛰거나 반복된다.
        assertThat(capturedJpql()).contains(
                "(o.placedAt < :cursorPlacedAt OR (o.placedAt = :cursorPlacedAt AND o.id < :cursorId))");
    }

    @Test
    void 커서_시각만_주면_거부하고_쿼리를_만들지도_않는다() {
        assertThatThrownBy(() -> repository.findByCustomer(Ids.newId(), null, null, null, PLACED_AT, null, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursorId");

        verify(entityManager, never()).createQuery(anyString(), eq(OrderEntity.class));
    }

    @Test
    void limit_이_1_미만이면_거부한다() {
        assertThatThrownBy(() -> repository.findByCustomer(Ids.newId(), null, null, null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void save_는_엔티티로_바꿔_persist_한다() {
        Order order = order();

        repository.save(order);

        ArgumentCaptor<OrderEntity> persisted = ArgumentCaptor.forClass(OrderEntity.class);
        verify(entityManager).persist(persisted.capture());
        assertThat(persisted.getValue().id()).isEqualTo(order.id());
    }

    @Test
    void update_는_관리_인스턴스를_찾아_고친다() {
        Order order = order();
        OrderEntity managed = OrderEntity.from(order);
        when(entityManager.find(OrderEntity.class, order.id())).thenReturn(managed);

        Order transitioned = OrderEntity.from(order).toDomain();
        transitioned.markPlanned(PLACED_AT.plusSeconds(30));
        repository.update(transitioned);

        // merge 가 아니라 관리 인스턴스 수정이어야 낙관적 락이 성립한다.
        verify(entityManager, never()).merge(any());
        assertThat(managed.status()).isEqualTo(OrderStatus.PLANNED);
    }

    @Test
    void 없는_주문을_갱신하면_NotFound_다() {
        Order order = order();
        when(entityManager.find(OrderEntity.class, order.id())).thenReturn(null);

        assertThatThrownBy(() -> repository.update(order))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void findById_는_없으면_빈_값이다() {
        UUID id = Ids.newId();
        when(entityManager.find(OrderEntity.class, id)).thenReturn(null);

        assertThat(repository.findById(id)).isEmpty();
    }

    @Test
    void null_인자는_즉시_거부한다() {
        assertThatThrownBy(() -> repository.save(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.update(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.findById(null)).isInstanceOf(NullPointerException.class);
    }
}
