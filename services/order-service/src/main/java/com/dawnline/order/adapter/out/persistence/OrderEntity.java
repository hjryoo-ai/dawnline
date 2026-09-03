package com.dawnline.order.adapter.out.persistence;

import com.dawnline.common.GeoPoint;
import com.dawnline.order.domain.DeliveryAddress;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.Parcel;
import com.dawnline.order.domain.PromisedWindow;
import com.dawnline.order.domain.ServiceTier;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code orders} 행 (DESIGN.md §5.1).
 *
 * <p><strong>도메인 {@link Order} 와 분리된 별도 클래스다</strong> (ADR-007). 도메인은 순수 자바여야
 * 하고(불변규칙 5), 애그리거트는 세터가 없어야 하는데(불변규칙 6) JPA 는 가변 필드와 무인자 생성자를
 * 요구한다. 한 클래스로 합치면 둘 중 하나를 포기하게 된다.
 *
 * <p>변환은 {@link #toDomain()}·{@link #from(Order)} 양방향이다. 이 두 메서드가 매핑의 유일한
 * 지점이라, 컬럼이 늘면 컴파일러가 여기 한 곳만 가리킨다.
 *
 * <h2>좌표를 {@code BigDecimal} 로 받는 이유</h2>
 * DDL 이 {@code NUMERIC(9,6)} 이다(불변규칙 9). {@code double} 로 매핑하면 Hibernate 가
 * {@code ddl-auto=validate} 에서 타입 불일치를 잡지 못하는 경로가 있고, 저장·조회를 왕복하며
 * 마지막 자리가 흔들릴 수 있다. 도메인은 {@code double}({@link GeoPoint})을 쓰되 경계에서 변환한다.
 */
@Entity
@Table(name = "orders")
public class OrderEntity {

    /** {@code NUMERIC(9,6)} 의 소수 자릿수. 변환할 때 이 스케일로 맞춘다. */
    private static final int COORDINATE_SCALE = 6;

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_tier", nullable = false, length = 16)
    private ServiceTier serviceTier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "address_line", nullable = false)
    private String addressLine;

    @Column(name = "postal_code", nullable = false, length = 10)
    private String postalCode;

    @Column(name = "lat", nullable = false, precision = 9, scale = COORDINATE_SCALE)
    private BigDecimal lat;

    @Column(name = "lng", nullable = false, precision = 9, scale = COORDINATE_SCALE)
    private BigDecimal lng;

    /**
     * DDL 이 {@code CHAR(7)} 이다(§5.1). {@code String} 은 기본으로 {@code VARCHAR} 에 매핑되므로
     * {@code ddl-auto=validate} 가 타입 불일치로 기동을 막는다 — {@code columnDefinition} 을 적어도
     * 그것은 생성용 힌트일 뿐 검증에는 쓰이지 않는다. JDBC 타입을 명시해야 맞는다.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "geohash7", nullable = false, length = 7)
    private String geohash7;

    @Column(name = "promised_start", nullable = false)
    private Instant promisedStart;

    @Column(name = "promised_end", nullable = false)
    private Instant promisedEnd;

    @Column(name = "weight_g", nullable = false)
    private int weightG;

    @Column(name = "volume_cm3", nullable = false)
    private int volumeCm3;

    @Column(name = "requires_cold", nullable = false)
    private boolean requiresCold;

    @Column(name = "hazmat", nullable = false)
    private boolean hazmat;

    /**
     * 낙관적 락. {@code @Version} 이 붙어 있으므로 <strong>Hibernate 가</strong> 증가시킨다 —
     * 도메인이 올리지 않는 이유는 {@link Order#version()} Javadoc 에 있다.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 품목. {@code EAGER} 인 이유: 주문을 읽는 모든 경로가 품목을 함께 쓴다(상태 조회 응답, 이벤트
     * 페이로드). {@code LAZY} 로 두면 트랜잭션 밖에서 열리는 순간
     * {@code LazyInitializationException} 이고, {@code open-in-view=false} 라 그 순간이 반드시 온다.
     * 한 주문의 품목은 상한이 200줄이라(도메인 불변식) 비용도 예측 가능하다.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    private List<OrderItemEntity> items = new ArrayList<>();

    /** JPA 전용. 애플리케이션 코드는 {@link #from(Order)} 를 쓴다. */
    protected OrderEntity() {
    }

    /**
     * 도메인 애그리거트를 행으로 옮긴다.
     *
     * @param order 도메인 주문
     */
    public static OrderEntity from(Order order) {
        OrderEntity entity = new OrderEntity();
        entity.id = order.id();
        entity.customerId = order.customerId();
        entity.serviceTier = order.serviceTier();
        entity.status = order.status();
        entity.applyAddress(order.address());
        entity.promisedStart = order.promisedWindow().start();
        entity.promisedEnd = order.promisedWindow().end();
        entity.applyParcel(order.parcel());
        entity.items = order.items().stream()
                .map(item -> new OrderItemEntity(item.lineNo(), item.sku(), item.qty()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        entity.placedAt = order.placedAt();
        entity.updatedAt = order.updatedAt();
        return entity;
    }

    /**
     * 이미 저장된 행에 도메인의 변경을 반영한다.
     *
     * <p>새 엔티티로 갈아치우지 않고 <em>기존 관리 인스턴스를 고치는</em> 이유는 낙관적 락이다.
     * {@code merge} 로 새 인스턴스를 밀어 넣으면 그 인스턴스의 {@code version} 이 기준이 되는데,
     * 도메인은 버전을 올리지 않으므로 읽은 시점의 값 그대로다 — 그 사이 다른 트랜잭션이 바꿔도
     * 충돌로 잡히지 않는다. 관리 인스턴스를 고치면 Hibernate 가 자기가 읽은 버전으로 UPDATE 한다.
     *
     * <p>불변 필드(id·customerId·주소·약속창·소포·품목·placedAt)는 건드리지 않는다.
     * 그것들이 바뀌는 유스케이스는 없으며, 있다면 새 주문이다.
     *
     * @param order 변경된 도메인 주문
     */
    public void applyStateOf(Order order) {
        if (!id.equals(order.id())) {
            throw new IllegalArgumentException(
                    "다른 주문의 상태를 반영할 수 없습니다: " + id + " ← " + order.id());
        }
        this.status = order.status();
        this.updatedAt = order.updatedAt();
    }

    /** 행을 도메인 애그리거트로 되살린다. */
    public Order toDomain() {
        DeliveryAddress address = new DeliveryAddress(
                addressLine, postalCode, new GeoPoint(lat.doubleValue(), lng.doubleValue()), geohash7.strip());
        List<OrderItem> domainItems = items.stream()
                .sorted(java.util.Comparator.comparingInt(OrderItemEntity::lineNo))
                .map(item -> new OrderItem(item.lineNo(), item.sku(), item.qty()))
                .toList();
        return Order.rehydrate(id, customerId, serviceTier, address,
                new PromisedWindow(new com.dawnline.common.TimeWindow(promisedStart, promisedEnd)),
                new Parcel(weightG, volumeCm3, requiresCold, hazmat),
                domainItems, status, placedAt, updatedAt, version);
    }

    /** 주문 id. */
    public UUID id() {
        return id;
    }

    /** 현재 상태. */
    public OrderStatus status() {
        return status;
    }

    /** 낙관적 락 버전. */
    public long version() {
        return version;
    }

    private void applyAddress(DeliveryAddress address) {
        this.addressLine = address.line();
        this.postalCode = address.postalCode();
        this.lat = BigDecimal.valueOf(address.point().lat()).setScale(COORDINATE_SCALE, java.math.RoundingMode.HALF_UP);
        this.lng = BigDecimal.valueOf(address.point().lng()).setScale(COORDINATE_SCALE, java.math.RoundingMode.HALF_UP);
        this.geohash7 = address.geohash7();
    }

    private void applyParcel(Parcel parcel) {
        this.weightG = parcel.weightG();
        this.volumeCm3 = parcel.volumeCm3();
        this.requiresCold = parcel.requiresCold();
        this.hazmat = parcel.hazmat();
    }
}
