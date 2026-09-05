package com.dawnline.order.config;

import com.dawnline.common.Ids;
import com.dawnline.messaging.outbox.OutboxAppender;
import com.dawnline.order.adapter.out.geo.AllTiersServiceableZones;
import com.dawnline.order.adapter.out.geo.PostalPrefixGeocoder;
import com.dawnline.order.adapter.out.messaging.OutboxOrderEvents;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.order.adapter.in.messaging.OrderProgressListener;
import com.dawnline.order.application.AdvanceOrderService;
import com.dawnline.order.application.ApplyFulfillmentPlanService;
import com.dawnline.order.application.CancelOrderService;
import com.dawnline.order.application.IdempotencyKeyCleaner;
import com.dawnline.order.application.OrderQueryService;
import com.dawnline.order.application.PlaceOrderService;
import com.dawnline.order.application.PlaceOrderTransaction;
import com.dawnline.order.application.port.in.AdvanceOrderUseCase;
import com.dawnline.order.application.port.in.ApplyFulfillmentPlanUseCase;
import com.dawnline.order.application.port.in.CancelOrderUseCase;
import com.dawnline.order.application.port.in.GetOrderUseCase;
import com.dawnline.order.application.port.in.ListOrdersUseCase;
import com.dawnline.order.application.port.in.PlaceOrderUseCase;
import com.dawnline.order.application.port.out.Geocoder;
import com.dawnline.order.application.port.out.IdempotencyCache;
import com.dawnline.order.application.port.out.IdempotencyRecords;
import com.dawnline.order.application.port.out.OrderEvents;
import com.dawnline.order.application.port.out.OrderRepository;
import com.dawnline.order.domain.DeliveryPromise;
import com.dawnline.order.domain.TierEligibility;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 도메인 서비스와 유스케이스 배선 (DESIGN.md §3.4, §5.1).
 *
 * <p>도메인·유스케이스 클래스에는 Spring 어노테이션이 없다(불변규칙 5). 그래서 배선이 여기 모여 있고,
 * 무엇이 무엇에 의존하는지가 한 화면에 보인다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderProperties.class)
@EnableScheduling
public class OrderApplicationConfig {

    /**
     * 우편번호 → 좌표 (§5.1 기본 구현).
     */
    @Bean
    public Geocoder geocoder() {
        return new PostalPrefixGeocoder();
    }

    /**
     * 권역 → 지원 티어. Phase 2 에 fulfillment-service 가 {@code zones} 를 가지면 교체된다.
     */
    @Bean
    public TierEligibility.ServiceableZones serviceableZones() {
        return new AllTiersServiceableZones();
    }

    /**
     * 티어 가능 여부 판정 (§5.1 도메인 서비스).
     *
     * @param zones 권역 조회
     * @param clock 시각 출처 — libs/messaging 이 저장 정밀도(마이크로초)로 잘라서 준다
     */
    @Bean
    public TierEligibility tierEligibility(TierEligibility.ServiceableZones zones, Clock clock) {
        return new TierEligibility(zones, clock);
    }

    /** 약속 배송창 계산 (§2.2). */
    @Bean
    public DeliveryPromise deliveryPromise() {
        return DeliveryPromise.standard();
    }

    /**
     * {@code order.placed} 발행 (outbox, 불변규칙 1).
     *
     * @param outbox 발행의 유일한 진입점
     */
    @Bean
    public OrderEvents orderEvents(OutboxAppender outbox) {
        return new OutboxOrderEvents(outbox);
    }

    /**
     * 접수 트랜잭션 경계. {@link PlaceOrderService} 와 <strong>별개 빈</strong>이어야
     * {@code @Transactional} 프록시를 지나간다.
     *
     * @param orders  주문 저장소
     * @param events  이벤트 발행
     * @param records 멱등 기록
     */
    @Bean
    public PlaceOrderTransaction placeOrderTransaction(OrderRepository orders, OrderEvents events,
            IdempotencyRecords records) {
        return new PlaceOrderTransaction(orders, events, records);
    }

    /**
     * 주문 접수 유스케이스.
     *
     * @param geocoder    우편번호 → 좌표
     * @param tiers       티어 판정
     * @param promises    약속창 계산
     * @param records     멱등 기록(진실)
     * @param cache       멱등 잠금(성능)
     * @param transaction 트랜잭션 경계
     * @param ids         UUIDv7 생성기
     * @param clock       접수 시각 출처 — 저장 정밀도로 잘린 시계여야 한다
     * @param properties  {@code dawnline.order.*}
     * @param meters      Micrometer 레지스트리
     */
    @Bean
    public PlaceOrderUseCase placeOrderUseCase(Geocoder geocoder, TierEligibility tiers, DeliveryPromise promises,
            IdempotencyRecords records, IdempotencyCache cache, PlaceOrderTransaction transaction,
            Ids ids, Clock clock, OrderProperties properties, MeterRegistry meters) {
        return new PlaceOrderService(geocoder, tiers, promises, records, cache, transaction,
                ids, clock, properties.idempotency().retention(), meters);
    }

    /**
     * 주문 취소 (§5.1). 트랜잭션 경계가 유스케이스 안에 있다 — 트랜잭션 밖에서 할 일이 없어
     * {@link PlaceOrderService} 처럼 쪼갤 이유가 없다.
     *
     * @param orders 주문 저장소
     * @param events 이벤트 발행
     * @param clock  전이 시각 출처
     */
    @Bean
    public CancelOrderUseCase cancelOrderUseCase(OrderRepository orders, OrderEvents events, Clock clock) {
        return new CancelOrderService(orders, events, clock);
    }

    /**
     * 주문 조회 (§5.1). 상세와 목록을 한 클래스가 구현하고 빈도 하나다 — 같은 저장소를 같은 방식으로
     * 읽는 두 메서드라 나눌 이유가 없다. 반환 타입을 구현 클래스로 두면 Spring 이
     * {@link GetOrderUseCase}·{@link ListOrdersUseCase} 양쪽 주입 지점에 이 빈을 꽂는다.
     * 인터페이스별로 빈을 따로 노출하면 같은 인스턴스가 두 이름으로 등록돼 주입이 모호해진다.
     *
     * @param orders 주문 저장소
     */
    @Bean
    public OrderQueryService orderQueryService(OrderRepository orders) {
        return new OrderQueryService(orders);
    }

    /**
     * 배송 진행 이벤트를 상태 머신에 적용 (§5.1, ADR-017).
     *
     * @param orders 주문 저장소
     */
    @Bean
    public AdvanceOrderUseCase advanceOrderUseCase(OrderRepository orders) {
        return new AdvanceOrderService(orders);
    }

    /**
     * {@code fulfillment.planned} 반영 (§5.2 6단계, ADR-017 경고, ADR-020 결정 3).
     *
     * <p>{@link AdvanceOrderUseCase} 와 나누는 이유는 ADR-017 이 미리 적어 두었다 — 이 이벤트는
     * 상태만 나르지 않고, 상태 전이가 stale 로 버려져도 함께 온 약속 개정은 사실이다.
     *
     * @param orders 주문 저장소
     */
    @Bean
    public ApplyFulfillmentPlanUseCase applyFulfillmentPlanUseCase(OrderRepository orders) {
        return new ApplyFulfillmentPlanService(orders);
    }

    /**
     * 주문 이벤트 리스너 (§4.1) — {@code order.dispatched}·{@code delivery.status}·
     * {@code fulfillment.planned}.
     *
     * <p>{@code IdempotentConsumer}·{@code EventJson} 은 {@code libs/messaging} 의 자동설정이 준다.
     *
     * @param consumer     멱등 게이트
     * @param advanceOrder 상태 전이 유스케이스
     * @param applyPlan    계획 반영 유스케이스 (전이 + 데이터 부착, ADR-017 경고)
     * @param json         이벤트 JSON 코덱
     * @param meters       Micrometer 레지스트리
     */
    @Bean
    public OrderProgressListener orderProgressListener(IdempotentConsumer consumer,
            AdvanceOrderUseCase advanceOrder, ApplyFulfillmentPlanUseCase applyPlan,
            EventJson json, MeterRegistry meters) {
        return new OrderProgressListener(consumer, advanceOrder, applyPlan, json, meters);
    }

    /**
     * 멱등 기록 보존 정리 (ADR-019).
     *
     * <p>{@code dawnline.order.idempotency.cleanup-enabled=false} 로 끌 수 있다. 끄면 테이블이
     * 자라기만 하므로, 여러 인스턴스 중 하나만 돌리고 싶을 때가 아니면 켜 둔다.
     *
     * @param records            멱등 기록 저장소
     * @param transactionManager 배치마다 트랜잭션을 여는 데 쓴다
     * @param clock              기준 시각
     * @param properties         {@code dawnline.order.idempotency.*}
     */
    @Bean
    @ConditionalOnProperty(prefix = "dawnline.order.idempotency", name = "cleanup-enabled",
            havingValue = "true", matchIfMissing = true)
    public IdempotencyKeyCleaner idempotencyKeyCleaner(IdempotencyRecords records,
            PlatformTransactionManager transactionManager, Clock clock, OrderProperties properties) {
        return new IdempotencyKeyCleaner(records, transactionManager, clock,
                properties.idempotency().batchSize(), properties.idempotency().maxBatchesPerRun());
    }
}
