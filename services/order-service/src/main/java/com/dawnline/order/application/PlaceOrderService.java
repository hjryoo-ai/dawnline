package com.dawnline.order.application;

import com.dawnline.common.GeoPoint;
import com.dawnline.order.OrderMetrics;
import com.dawnline.common.Ids;
import com.dawnline.common.error.CommonErrorCode;
import com.dawnline.common.error.ConflictException;
import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.ValidationException;
import com.dawnline.order.application.port.in.OrderAccepted;
import com.dawnline.order.application.port.in.PlaceOrderCommand;
import com.dawnline.order.application.port.in.PlaceOrderResult;
import com.dawnline.order.application.port.in.PlaceOrderUseCase;
import com.dawnline.order.application.port.out.Geocoder;
import com.dawnline.order.application.port.out.IdempotencyCache;
import com.dawnline.order.application.port.out.IdempotencyClaim;
import com.dawnline.order.application.port.out.IdempotencyRecord;
import com.dawnline.order.application.port.out.IdempotencyRecords;
import com.dawnline.order.domain.DeliveryAddress;
import com.dawnline.order.domain.DeliveryPromise;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.ServiceTier;
import com.dawnline.order.domain.TierEligibility;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 주문 접수 유스케이스 (DESIGN.md §5.1 "멱등 처리 흐름", ADR-018).
 *
 * <p>순서는 이렇다.
 *
 * <ol>
 *   <li>요청 지문을 만든다.</li>
 *   <li>{@code idempotency_keys} 를 읽는다 — 지문이 다르면 422, {@code DONE} 이면 저장된 응답 재생,
 *       {@code IN_PROGRESS} 면 409.</li>
 *   <li>행이 없으면 Redis 잠금을 시도한다. 이미 잡혀 있으면 <em>한 번 더</em> DB 를 읽고
 *       (그 사이 커밋됐을 수 있다) 그래도 없으면 409. Redis 가 죽어 있으면 잠금 없이 진행한다.</li>
 *   <li>주문을 만들고 {@link PlaceOrderTransaction} 에 넘긴다.</li>
 *   <li>커밋 후 Redis 키를 {@code DONE} 으로, 실패하면 잠금을 푼다.</li>
 * </ol>
 *
 * <p><strong>Redis 가 없어도 답은 같다</strong>(불변규칙 7). 동시에 들어온 같은 키의 두 요청 중
 * 하나만 성공한다는 보장은 {@code idempotency_keys} 의 기본 키가 준다. Redis 는 진 쪽이 헛일을
 * 하지 않게 막아 줄 뿐이다.
 */
public class PlaceOrderService implements PlaceOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderService.class);

    private final Geocoder geocoder;
    private final TierEligibility tiers;
    private final DeliveryPromise promises;
    private final IdempotencyRecords records;
    private final IdempotencyCache cache;
    private final PlaceOrderTransaction transaction;
    private final Ids ids;
    private final Clock clock;
    private final Duration recordRetention;
    private final MeterRegistry meters;

    /**
     * @param geocoder        우편번호 → 좌표
     * @param tiers           티어 가능 여부 판정
     * @param promises        약속 배송창 계산 (§2.2)
     * @param records         멱등 기록 저장소 (진실)
     * @param cache           멱등 잠금 (성능)
     * @param transaction     트랜잭션 경계
     * @param ids             UUIDv7 생성기 (불변규칙 10)
     * @param clock           접수 시각 출처 (불변규칙 12)
     * @param recordRetention 멱등 기록 보관 기간
     * @param meters          Micrometer 레지스트리 (§9.1)
     */
    public PlaceOrderService(Geocoder geocoder, TierEligibility tiers, DeliveryPromise promises,
            IdempotencyRecords records, IdempotencyCache cache, PlaceOrderTransaction transaction,
            Ids ids, Clock clock, Duration recordRetention, MeterRegistry meters) {
        this.geocoder = Objects.requireNonNull(geocoder, "geocoder");
        this.tiers = Objects.requireNonNull(tiers, "tiers");
        this.promises = Objects.requireNonNull(promises, "promises");
        this.records = Objects.requireNonNull(records, "records");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.recordRetention = Objects.requireNonNull(recordRetention, "recordRetention");
        this.meters = Objects.requireNonNull(meters, "meters");
        if (recordRetention.isNegative() || recordRetention.isZero()) {
            throw new IllegalArgumentException("recordRetention 은 양수여야 합니다: " + recordRetention);
        }
    }

    @Override
    public PlaceOrderResult place(PlaceOrderCommand command) {
        Objects.requireNonNull(command, "command");
        String key = command.idempotencyKey();
        String fingerprint = command.fingerprint();

        Optional<IdempotencyRecord> known = records.find(key);
        if (known.isPresent()) {
            return replay(known.get(), fingerprint);
        }

        IdempotencyCache.Lock lock = cache.tryLock(key);
        if (lock == IdempotencyCache.Lock.HELD) {
            // 우리가 DB 를 읽은 뒤 잠금을 시도하기까지 사이에 다른 요청이 커밋했을 수 있다.
            // 그 경우 잠금은 이미 DONE 표시이고, DB 에는 재생할 응답이 있다.
            return records.find(key)
                    .map(stored -> replay(stored, fingerprint))
                    .orElseThrow(PlaceOrderService::inFlight);
        }

        Placement placement = build(command);
        Order order = placement.order();
        IdempotencyClaim claim = new IdempotencyClaim(key, fingerprint,
                order.placedAt(), order.placedAt().plus(recordRetention));
        try {
            OrderAccepted accepted = transaction.commit(order, placement.cutoffAt(), claim);
            cache.markDone(key);
            countPlaced(accepted);
            log.info("주문을 접수했습니다. orderId={}, tier={}, items={}",
                    accepted.orderId(), accepted.serviceTier(), order.items().size());
            return new PlaceOrderResult(accepted, false);
        } catch (RuntimeException e) {
            if (lock == IdempotencyCache.Lock.ACQUIRED) {
                // 우리가 잡은 잠금만 푼다. 잡지 않은 키를 지우면 남의 in-flight 표시를 없애는 것이다.
                cache.release(key);
            }
            throw e;
        }
    }

    /**
     * 저장된 기록으로 답한다 — 같은 요청이면 재생, 다른 요청이면 422.
     *
     * <p>기록이 있다는 것은 곧 그 요청이 끝났다는 뜻이다(ADR-018·019). 처리 중 상태는 이 테이블에
     * 없으므로 여기서 갈라지는 경우도 둘뿐이다.
     */
    private PlaceOrderResult replay(IdempotencyRecord stored, String fingerprint) {
        if (!stored.requestHash().equals(fingerprint)) {
            throw new DomainException(CommonErrorCode.UNPROCESSABLE_REQUEST,
                    "같은 멱등 키로 다른 요청이 왔습니다",
                    // 지문 자체는 넣지 않는다. 요청 본문을 되짚을 수 있는 값이라 오류 응답에 남길 이유가 없다.
                    Map.of("reason", "idempotency-key-reused-with-different-body"));
        }
        OrderAccepted response = stored.response();
        countReplay(response);
        return new PlaceOrderResult(response, true);
    }

    /**
     * 접수 시점에 정해지는 것들. 애그리거트에 담기는 것과 이벤트에만 실리는 것이 함께 나온다.
     *
     * @param order    저장될 주문
     * @param cutoffAt 이 주문이 실릴 웨이브의 컷오프. {@code orders} 에는 저장하지 않는다
     */
    private record Placement(Order order, Instant cutoffAt) {
    }

    /**
     * 주소를 좌표로 바꾸고 티어를 확인한 뒤 애그리거트를 만든다. 전부 순수 계산이라 트랜잭션 밖이다.
     */
    private Placement build(PlaceOrderCommand command) {
        Instant now = clock.instant();
        ServiceTier tier = command.serviceTier();

        GeoPoint point = geocoder.locate(command.postalCode(), command.addressLine())
                .orElseThrow(() -> ValidationException.field("postalCode", command.postalCode(),
                        "좌표를 찾을 수 없는 우편번호입니다"));
        DeliveryAddress address = DeliveryAddress.of(command.addressLine(), command.postalCode(), point);

        if (!tiers.isEligible(address, tier)) {
            Set<ServiceTier> eligible = tiers.eligibleTiers(address);
            throw new DomainException(CommonErrorCode.UNPROCESSABLE_REQUEST,
                    tier + " 티어는 이 지역에 제공되지 않습니다",
                    Map.of("serviceTier", tier.name(),
                            "geohash5", address.geohash5(),
                            // 사용자가 다음에 뭘 해야 할지 모르는 422 는 쓸모가 적다.
                            "eligibleTiers", eligible.stream().map(Enum::name).collect(Collectors.joining(","))));
        }

        DeliveryPromise.Promise promise = promises.promiseFor(tier, now);
        Order order = Order.place(ids.newUuid(), command.customerId(), tier, address, promise.window(),
                command.parcel(), command.items(), now);
        return new Placement(order, promise.cutoffAt());
    }

    /**
     * 접수 수를 센다 (§9.1). <strong>커밋된 뒤에만</strong> 부른다 — 롤백된 주문을 세면 지표가
     * 실제 주문량보다 많아지고, 그 차이는 장애 때 가장 커진다.
     *
     * <p>재생(같은 멱등 키의 재요청)은 세지 않는다. 새 주문이 아니기 때문이고, 세면 클라이언트의
     * 재시도 패턴이 주문량 지표를 부풀린다.
     */
    private void countPlaced(OrderAccepted accepted) {
        Counter.builder(OrderMetrics.ORDERS_PLACED)
                .description("접수된 주문 수 (§9.1)")
                .tag(OrderMetrics.TAG_TIER, accepted.serviceTier().name())
                .register(meters)
                .increment();
    }

    /**
     * 재생을 센다 (§9.1). {@code ORDERS_PLACED} 와 나눠 두어야 "요청이 늘었다" 와 "클라이언트가
     * 재시도를 퍼붓고 있다" 를 구분할 수 있다.
     */
    private void countReplay(OrderAccepted response) {
        Counter.builder(OrderMetrics.IDEMPOTENT_REPLAYS)
                .description("멱등 재생 횟수 — 저장된 응답을 다시 준 횟수 (§9.1)")
                .tag(OrderMetrics.TAG_TIER, response.serviceTier().name())
                .register(meters)
                .increment();
    }

    private static ConflictException inFlight() {
        return new ConflictException("같은 멱등 키의 요청이 처리 중입니다");
    }
}
