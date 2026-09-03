package com.dawnline.order.domain;

import com.dawnline.common.error.IllegalStateTransitionException;
import com.dawnline.common.error.ValidationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 주문 애그리거트 루트 (DESIGN.md §5.1).
 *
 * <p><strong>순수 자바다.</strong> Spring·JPA 어노테이션이 없다 (CLAUDE.md 불변규칙 5, ADR-007).
 * 영속화는 {@code adapter.out.persistence} 의 별도 엔티티가 맡고 이 클래스로 변환한다.
 *
 * <h2>상태 전이</h2>
 * 세터가 없다. 상태는 {@link #markPlanned}·{@link #markDispatched}·{@link #markDelivered}·
 * {@link #markFailed}·{@link #cancel} 로만 바뀌고, 허용되지 않은 전이는
 * {@link IllegalStateTransitionException}(HTTP 409)이다 (불변규칙 6).
 * 전이표 자체는 {@link OrderStatus#allowedTransitions()} 한 곳에 있다.
 *
 * <h2>왜 전이 메서드가 시각을 인자로 받는가</h2>
 * {@code Instant.now()} 를 안에서 부르면 전이 순서·경과 시간을 검증하는 테스트를 쓸 수 없고,
 * 리스너가 처리한 시각과 이벤트가 발생한 시각이 뒤섞인다. 시간은 주입한다 (불변규칙 12).
 * 애그리거트는 {@code Clock} 을 들고 있지 않다 — 호출자(유스케이스)가 이미 갖고 있고,
 * 애그리거트가 시계를 필드로 가지면 그것도 상태가 된다.
 *
 * <h2>{@code version}</h2>
 * 낙관적 락 버전(§5.1 DDL)이다. 여기서 올리지 않는다. 증가는 영속화 계층의 책임이고,
 * 도메인이 올리면 저장하지 않은 변경에도 버전이 움직여 충돌 판정이 어긋난다.
 */
public final class Order {

    /** 한 주문의 품목 줄 수 상한. 오입력·악의적 요청이 트랜잭션을 부풀리는 것을 막는다. */
    private static final int MAX_ITEMS = 200;

    private final UUID id;
    private final UUID customerId;
    private final ServiceTier serviceTier;
    private final DeliveryAddress address;
    private final PromisedWindow promisedWindow;
    private final Parcel parcel;
    private final List<OrderItem> items;
    private final Instant placedAt;
    private final long version;

    private OrderStatus status;
    private Instant updatedAt;

    private Order(UUID id, UUID customerId, ServiceTier serviceTier, DeliveryAddress address,
            PromisedWindow promisedWindow, Parcel parcel, List<OrderItem> items,
            OrderStatus status, Instant placedAt, Instant updatedAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.serviceTier = Objects.requireNonNull(serviceTier, "serviceTier");
        this.address = Objects.requireNonNull(address, "address");
        this.promisedWindow = Objects.requireNonNull(promisedWindow, "promisedWindow");
        this.parcel = Objects.requireNonNull(parcel, "parcel");
        this.items = List.copyOf(requireItems(items));
        this.status = Objects.requireNonNull(status, "status");
        this.placedAt = Objects.requireNonNull(placedAt, "placedAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /**
     * 새 주문을 접수한다. 상태는 {@link OrderStatus#PLACED} 로 시작한다.
     *
     * @param id             UUIDv7 ({@code Ids.newId()}, 불변규칙 10)
     * @param customerId     고객 id
     * @param serviceTier    서비스 티어
     * @param address        배송지
     * @param promisedWindow 약속 배송창
     * @param parcel         소포 제원
     * @param items          품목 (1개 이상)
     * @param placedAt       접수 시각
     */
    public static Order place(UUID id, UUID customerId, ServiceTier serviceTier, DeliveryAddress address,
            PromisedWindow promisedWindow, Parcel parcel, List<OrderItem> items, Instant placedAt) {
        return new Order(id, customerId, serviceTier, address, promisedWindow, parcel, items,
                OrderStatus.PLACED, placedAt, placedAt, 0L);
    }

    /**
     * 저장된 상태에서 애그리거트를 되살린다. 영속화 어댑터 전용이다.
     *
     * <p>{@link #place} 와 달리 상태 검증을 하지 않는다 — 이미 한 번 유효했던 값이고,
     * 여기서 다시 막으면 규칙이 바뀌었을 때 기존 주문을 읽지 못하게 된다.
     *
     * @param id             주문 id
     * @param customerId     고객 id
     * @param serviceTier    서비스 티어
     * @param address        배송지
     * @param promisedWindow 약속 배송창
     * @param parcel         소포 제원
     * @param items          품목
     * @param status         저장된 상태
     * @param placedAt       접수 시각
     * @param updatedAt      마지막 갱신 시각
     * @param version        낙관적 락 버전
     */
    public static Order rehydrate(UUID id, UUID customerId, ServiceTier serviceTier, DeliveryAddress address,
            PromisedWindow promisedWindow, Parcel parcel, List<OrderItem> items, OrderStatus status,
            Instant placedAt, Instant updatedAt, long version) {
        return new Order(id, customerId, serviceTier, address, promisedWindow, parcel, items,
                status, placedAt, updatedAt, version);
    }

    /**
     * 웨이브에 편성됐다 ({@code fulfillment.planned} 수신).
     *
     * @param at 전이 시각
     */
    public void markPlanned(Instant at) {
        transitionTo(OrderStatus.PLANNED, at);
    }

    /**
     * 라우트에 배정돼 배송이 시작됐다 ({@code order.dispatched} 수신).
     *
     * @param at 전이 시각
     */
    public void markDispatched(Instant at) {
        transitionTo(OrderStatus.DISPATCHED, at);
    }

    /**
     * 배달 완료 ({@code delivery.status} = COMPLETED).
     *
     * @param at 전이 시각
     */
    public void markDelivered(Instant at) {
        transitionTo(OrderStatus.DELIVERED, at);
    }

    /**
     * 배달 실패 ({@code delivery.status} = FAILED).
     *
     * @param at 전이 시각
     */
    public void markFailed(Instant at) {
        transitionTo(OrderStatus.FAILED, at);
    }

    /**
     * 고객 취소. {@code PLACED}·{@code PLANNED} 에서만 가능하다 (§5.1: 이후는 409).
     *
     * @param at 전이 시각
     */
    public void cancel(Instant at) {
        transitionTo(OrderStatus.CANCELLED, at);
    }

    private void transitionTo(OrderStatus next, Instant at) {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(at, "at");
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateTransitionException("Order", status, next);
        }
        this.status = next;
        this.updatedAt = at;
    }

    /** 주문 id. */
    public UUID id() {
        return id;
    }

    /** 고객 id. */
    public UUID customerId() {
        return customerId;
    }

    /** 서비스 티어. */
    public ServiceTier serviceTier() {
        return serviceTier;
    }

    /** 배송지. */
    public DeliveryAddress address() {
        return address;
    }

    /** 약속 배송창. */
    public PromisedWindow promisedWindow() {
        return promisedWindow;
    }

    /** 소포 제원. */
    public Parcel parcel() {
        return parcel;
    }

    /** 품목 (불변). */
    public List<OrderItem> items() {
        return items;
    }

    /** 현재 상태. */
    public OrderStatus status() {
        return status;
    }

    /** 접수 시각. */
    public Instant placedAt() {
        return placedAt;
    }

    /** 마지막 상태 변경 시각. */
    public Instant updatedAt() {
        return updatedAt;
    }

    /** 낙관적 락 버전. */
    public long version() {
        return version;
    }

    /**
     * §4.5 의 파티션 키. 같은 주문의 이벤트는 같은 파티션으로 가야 순서가 보장된다.
     *
     * <p>주문 id 를 쓴다 — 배송지 geohash 가 아니다. geohash 는 stop 통합의 키이지 순서의 단위가
     * 아니고, 주소가 바뀌면(재배송) 같은 주문의 이벤트가 다른 파티션으로 흩어진다.
     */
    public String partitionKey() {
        return id.toString();
    }

    /**
     * 애그리거트 식별만 담는다. 전체 주소·고객 식별 정보는 로그 금지다 (CLAUDE.md 로그 규칙).
     * {@code customerId} 도 넣지 않는다 — MDC 의 {@code orderId} 로 충분히 추적된다 (§9.3).
     */
    @Override
    public String toString() {
        return "Order[id=" + id + ", status=" + status + ", tier=" + serviceTier + "]";
    }

    private static List<OrderItem> requireItems(@Nullable List<OrderItem> items) {
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) {
            throw ValidationException.field("items", 0, "1개 이상이어야 합니다");
        }
        if (items.size() > MAX_ITEMS) {
            throw ValidationException.field("items", items.size(), MAX_ITEMS + "개 이하여야 합니다");
        }
        List<Short> lineNumbers = new ArrayList<>(items.size());
        for (OrderItem item : items) {
            Objects.requireNonNull(item, "items 에 null 이 있습니다");
            if (lineNumbers.contains(item.lineNo())) {
                // (order_id, line_no) 가 PK 다. 중복은 DB 까지 가면 제약 위반으로 트랜잭션이
                // 통째로 중단되므로, 그 전에 이유가 분명한 예외로 거른다.
                throw ValidationException.field("items[].lineNo", item.lineNo(), "중복될 수 없습니다");
            }
            lineNumbers.add(item.lineNo());
        }
        return items;
    }
}
