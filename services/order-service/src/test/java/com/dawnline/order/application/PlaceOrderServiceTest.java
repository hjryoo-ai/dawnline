package com.dawnline.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.error.ConflictException;
import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.ValidationException;
import com.dawnline.order.application.port.in.OrderAccepted;
import com.dawnline.order.application.port.in.PlaceOrderCommand;
import com.dawnline.order.application.port.in.PlaceOrderResult;
import com.dawnline.order.application.port.out.Geocoder;
import com.dawnline.order.application.port.out.IdempotencyCache;
import com.dawnline.order.application.port.out.IdempotencyClaim;
import com.dawnline.order.application.port.out.IdempotencyRecord;
import com.dawnline.order.application.port.out.IdempotencyRecords;
import com.dawnline.order.domain.DeliveryPromise;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.Parcel;
import com.dawnline.order.domain.ServiceTier;
import com.dawnline.order.OrderMetrics;
import com.dawnline.order.domain.TierEligibility;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 멱등 처리 흐름 (DESIGN.md §5.1, ADR-018).
 *
 * <p>여기서 보는 것은 <strong>어떤 경로로 어떤 답이 나오는가</strong>다. 실제 DB·Redis 왕복은
 * {@code IdempotencyRecordsIT} 와 Phase 1-8 의 통합 테스트가 본다.
 */
@DisplayName("PlaceOrderService — 멱등 처리 흐름")
class PlaceOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final UUID CUSTOMER = Ids.newId();
    private static final GeoPoint GANGNAM = GeoPoint.of(37.4979, 127.0276);

    private Geocoder geocoder;
    private Set<ServiceTier> supportedTiers;
    private IdempotencyRecords records;
    private IdempotencyCache cache;
    private PlaceOrderTransaction transaction;
    private MeterRegistry meters;
    private PlaceOrderService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        geocoder = mock(Geocoder.class);
        when(geocoder.locate(anyString(), anyString())).thenReturn(Optional.of(GANGNAM));

        supportedTiers = Set.of(ServiceTier.values());
        TierEligibility tiers = new TierEligibility(geohash5 -> supportedTiers, clock);

        records = mock(IdempotencyRecords.class);
        when(records.find(anyString())).thenReturn(Optional.empty());

        cache = mock(IdempotencyCache.class);
        when(cache.tryLock(anyString())).thenReturn(IdempotencyCache.Lock.ACQUIRED);

        transaction = mock(PlaceOrderTransaction.class);
        when(transaction.commit(any(), any(), any()))
                .thenAnswer(invocation -> OrderAccepted.of(invocation.getArgument(0, Order.class)));

        meters = new SimpleMeterRegistry();
        service = new PlaceOrderService(geocoder, tiers, DeliveryPromise.standard(), records, cache,
                transaction, new Ids(clock, new Random(42)), clock, RETENTION, meters);
    }

    private static PlaceOrderCommand command() {
        return new PlaceOrderCommand("idem-1", CUSTOMER, ServiceTier.DAWN,
                "서울 강남구 테헤란로 1", "06236",
                new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1001", 2)));
    }

    private Order committedOrder() {
        ArgumentCaptor<Order> order = ArgumentCaptor.forClass(Order.class);
        verify(transaction).commit(order.capture(), any(), any());
        return order.getValue();
    }

    private IdempotencyClaim committedClaim() {
        ArgumentCaptor<IdempotencyClaim> claim = ArgumentCaptor.forClass(IdempotencyClaim.class);
        verify(transaction).commit(any(), any(), claim.capture());
        return claim.getValue();
    }

    @Test
    void 새_요청은_주문을_만들고_커밋한_뒤_Redis_를_DONE_으로_표시한다() {
        PlaceOrderResult result = service.place(command());

        assertThat(result.replayed()).isFalse();
        assertThat(result.order().status()).isEqualTo(OrderStatus.PLACED);
        assertThat(result.order().placedAt()).isEqualTo(NOW);
        // §2.2 DAWN: 익일 00:00–07:00 KST
        assertThat(result.order().promisedStart()).isEqualTo(Instant.parse("2026-09-03T15:00:00Z"));
        verify(cache).markDone("idem-1");
        verify(cache, never()).release(anyString());
    }

    @Test
    void 접수_시각과_주소가_애그리거트에_그대로_들어간다() {
        service.place(command());

        Order order = committedOrder();
        assertThat(order.customerId()).isEqualTo(CUSTOMER);
        assertThat(order.placedAt()).isEqualTo(NOW);
        assertThat(order.address().point()).isEqualTo(GeoPoint.of(37.4979, 127.0276));
        assertThat(order.items()).containsExactly(new OrderItem((short) 1, "SKU-1001", 2));
    }

    @Test
    void 멱등_기록의_보관_만료는_접수_시각에_보관_기간을_더한_값이다() {
        service.place(command());

        IdempotencyClaim claim = committedClaim();
        assertThat(claim.key()).isEqualTo("idem-1");
        assertThat(claim.requestHash()).isEqualTo(command().fingerprint());
        assertThat(claim.createdAt()).isEqualTo(NOW);
        assertThat(claim.expiresAt()).isEqualTo(NOW.plus(RETENTION));
    }

    private double placedCount(ServiceTier tier) {
        var counter = meters.find(OrderMetrics.ORDERS_PLACED)
                .tag(OrderMetrics.TAG_TIER, tier.name()).counter();
        return counter == null ? 0 : counter.count();
    }

    private double replayCount(ServiceTier tier) {
        var counter = meters.find(OrderMetrics.IDEMPOTENT_REPLAYS)
                .tag(OrderMetrics.TAG_TIER, tier.name()).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void 접수하면_티어별로_센다() {
        service.place(command());

        assertThat(placedCount(ServiceTier.DAWN)).isEqualTo(1);
        assertThat(replayCount(ServiceTier.DAWN)).as("새 접수는 재생이 아니다").isZero();
    }

    @Test
    void 커밋에_실패하면_세지_않는다() {
        // 롤백된 주문을 세면 지표가 실제 주문량보다 많아지고, 그 차이는 장애 때 가장 커진다.
        doThrow(new IllegalStateException("boom")).when(transaction).commit(any(), any(), any());

        assertThatThrownBy(() -> service.place(command())).isInstanceOf(IllegalStateException.class);

        assertThat(placedCount(ServiceTier.DAWN)).isZero();
    }

    @Test
    void 완료된_기록이_있으면_저장된_응답을_재생한다() {
        OrderAccepted stored = new OrderAccepted(Ids.newId(), OrderStatus.PLACED, ServiceTier.DAWN,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), NOW);
        when(records.find("idem-1")).thenReturn(Optional.of(new IdempotencyRecord(
                command().fingerprint(), 201, stored)));

        PlaceOrderResult result = service.place(command());

        assertThat(result.replayed()).isTrue();
        assertThat(result.order()).isEqualTo(stored);
        // 재생은 아무것도 쓰지 않는다 — 잠금조차 잡지 않는다.
        verifyNoInteractions(transaction, cache);
        // 재생은 새 주문이 아니다. 세면 클라이언트의 재시도 패턴이 주문량 지표를 부풀린다.
        assertThat(placedCount(ServiceTier.DAWN)).isZero();
        // 대신 별도 카운터로 센다 — 그래야 재시도 폭주와 실제 주문 증가를 구분할 수 있다.
        assertThat(replayCount(ServiceTier.DAWN)).isEqualTo(1);
    }

    @Test
    void 같은_키에_다른_본문이면_422_다() {
        when(records.find("idem-1")).thenReturn(Optional.of(new IdempotencyRecord(
                "0".repeat(64), 201,
                new OrderAccepted(Ids.newId(), OrderStatus.PLACED, ServiceTier.DAWN,
                        NOW, NOW.plusSeconds(1), NOW))));

        assertThatThrownBy(() -> service.place(command()))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).status()).isEqualTo(422));
        verifyNoInteractions(transaction);
    }

    @Test
    void 다른_본문_오류_응답에_지문을_담지_않는다() {
        when(records.find("idem-1")).thenReturn(Optional.of(new IdempotencyRecord(
                "0".repeat(64), 201,
                new OrderAccepted(Ids.newId(), OrderStatus.PLACED, ServiceTier.DAWN,
                        NOW, NOW.plusSeconds(1), NOW))));

        assertThatThrownBy(() -> service.place(command()))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(DomainException.class))
                .extracting(DomainException::details)
                .satisfies(details -> assertThat(details.values().toString()).doesNotContain("0000"));
    }

    @Test
    void 잠금이_이미_잡혀_있고_기록도_없으면_409_다() {
        when(cache.tryLock("idem-1")).thenReturn(IdempotencyCache.Lock.HELD);

        assertThatThrownBy(() -> service.place(command()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("처리 중");
        verifyNoInteractions(transaction);
    }

    @Test
    void 잠금_시도와_DB_조회_사이에_커밋됐으면_재생한다() {
        // 첫 조회에서는 없었는데 잠금이 잡혀 있다 = 그 사이 다른 요청이 끝냈을 수 있다.
        // 한 번 더 읽지 않으면 정상적으로 완료된 요청에 409 를 준다.
        OrderAccepted stored = new OrderAccepted(Ids.newId(), OrderStatus.PLACED, ServiceTier.DAWN,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), NOW);
        when(cache.tryLock("idem-1")).thenReturn(IdempotencyCache.Lock.HELD);
        when(records.find("idem-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new IdempotencyRecord(
                        command().fingerprint(), 201, stored)));

        PlaceOrderResult result = service.place(command());

        assertThat(result.replayed()).isTrue();
        assertThat(result.order()).isEqualTo(stored);
        verifyNoInteractions(transaction);
    }

    @Test
    void Redis_가_죽어_있으면_잠금_없이_진행한다() {
        // 불변규칙 7 — 캐시가 사라져도 정확성은 DB 가 지킨다. 접수를 막으면 안 된다.
        when(cache.tryLock("idem-1")).thenReturn(IdempotencyCache.Lock.UNAVAILABLE);

        PlaceOrderResult result = service.place(command());

        assertThat(result.replayed()).isFalse();
        verify(transaction).commit(any(), any(), any());
    }

    @Test
    void 커밋에_실패하면_잡았던_잠금을_푼다() {
        // 풀지 않으면 30초 동안 재시도가 409 가 된다.
        // doThrow 를 쓰는 이유: when(mock.method(...)) 형태는 기존 thenAnswer 스텁을 한 번 실행한다.
        doThrow(new IllegalStateException("boom")).when(transaction).commit(any(), any(), any());

        assertThatThrownBy(() -> service.place(command())).isInstanceOf(IllegalStateException.class);

        verify(cache).release("idem-1");
        verify(cache, never()).markDone(anyString());
    }

    @Test
    void 잠그지_않은_키는_실패해도_지우지_않는다() {
        // Redis 가 죽어 있는 동안 우리가 잡지 않은 키를 지우면, 그 사이 살아난 Redis 에서
        // 다른 요청의 in-flight 표시를 없애게 된다.
        when(cache.tryLock("idem-1")).thenReturn(IdempotencyCache.Lock.UNAVAILABLE);
        doThrow(new IllegalStateException("boom")).when(transaction).commit(any(), any(), any());

        assertThatThrownBy(() -> service.place(command())).isInstanceOf(IllegalStateException.class);

        verify(cache, never()).release(anyString());
    }

    @Test
    void 좌표를_찾을_수_없는_우편번호는_400_이고_트랜잭션을_열지_않는다() {
        when(geocoder.locate(anyString(), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.place(command()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("postalCode");
        verifyNoInteractions(transaction);
    }

    @Test
    void 제공되지_않는_티어는_422_이고_가능한_티어를_알려_준다() {
        supportedTiers = Set.of(ServiceTier.NEXT_DAY);

        assertThatThrownBy(() -> service.place(command()))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    DomainException domain = (DomainException) e;
                    assertThat(domain.status()).isEqualTo(422);
                    assertThat(domain.details()).containsEntry("serviceTier", "DAWN");
                    assertThat(domain.details()).containsEntry("eligibleTiers", "NEXT_DAY");
                });
        verifyNoInteractions(transaction);
    }

    @Test
    void 생성자는_잘못된_인자를_거부한다() {
        assertThatThrownBy(() -> new PlaceOrderService(geocoder, null, DeliveryPromise.standard(),
                records, cache, transaction, new Ids(Clock.systemUTC(), new Random(1)),
                Clock.systemUTC(), RETENTION, meters))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlaceOrderService(geocoder,
                new TierEligibility(geohash5 -> Set.of(), Clock.systemUTC()), DeliveryPromise.standard(),
                records, cache, transaction, new Ids(Clock.systemUTC(), new Random(1)),
                Clock.systemUTC(), Duration.ZERO, meters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recordRetention");
        assertThatThrownBy(() -> service.place(null)).isInstanceOf(NullPointerException.class);
    }
}
