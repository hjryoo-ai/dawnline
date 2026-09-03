package com.dawnline.order.config;

import com.dawnline.common.Ids;
import com.dawnline.messaging.outbox.OutboxAppender;
import com.dawnline.order.adapter.out.geo.AllTiersServiceableZones;
import com.dawnline.order.adapter.out.geo.PostalPrefixGeocoder;
import com.dawnline.order.adapter.out.messaging.OutboxOrderEvents;
import com.dawnline.order.application.PlaceOrderService;
import com.dawnline.order.application.PlaceOrderTransaction;
import com.dawnline.order.application.port.in.PlaceOrderUseCase;
import com.dawnline.order.application.port.out.Geocoder;
import com.dawnline.order.application.port.out.IdempotencyCache;
import com.dawnline.order.application.port.out.IdempotencyRecords;
import com.dawnline.order.application.port.out.OrderEvents;
import com.dawnline.order.application.port.out.OrderRepository;
import com.dawnline.order.domain.DeliveryPromise;
import com.dawnline.order.domain.TierEligibility;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 도메인 서비스와 유스케이스 배선 (DESIGN.md §3.4, §5.1).
 *
 * <p>도메인·유스케이스 클래스에는 Spring 어노테이션이 없다(불변규칙 5). 그래서 배선이 여기 모여 있고,
 * 무엇이 무엇에 의존하는지가 한 화면에 보인다.
 */
@Configuration(proxyBeanMethods = false)
public class OrderApplicationConfig {

    /**
     * 멱등 기록 보관 기간. §7.2 의 Redis 키 TTL(24h)과 같은 값이다 — 두 경로가 같은 기간 동안
     * 같은 답을 주도록.
     *
     * <p>{@code expires_at} 은 정리 기준일 뿐이고 지금은 지우는 배치가 없다. 행이 남아 있는 동안은
     * 재생이 계속 되므로, 만료가 지나 <em>더 이상 못 지우는</em> 방향의 위험은 없다.
     */
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

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
     */
    @Bean
    public PlaceOrderUseCase placeOrderUseCase(Geocoder geocoder, TierEligibility tiers, DeliveryPromise promises,
            IdempotencyRecords records, IdempotencyCache cache, PlaceOrderTransaction transaction,
            Ids ids, Clock clock) {
        return new PlaceOrderService(geocoder, tiers, promises, records, cache, transaction,
                ids, clock, IDEMPOTENCY_RETENTION);
    }
}
