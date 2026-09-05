package com.dawnline.fulfillment.config;

import com.dawnline.common.Ids;
import com.dawnline.common.TierSchedule;
import com.dawnline.fulfillment.adapter.in.messaging.OrderEventListener;
import com.dawnline.fulfillment.adapter.out.messaging.OutboxFulfillmentEvents;
import com.dawnline.fulfillment.application.CancelFulfillmentOrderService;
import com.dawnline.fulfillment.application.FcCandidateAssembler;
import com.dawnline.fulfillment.application.PlanOrderService;
import com.dawnline.fulfillment.application.FulfillmentRetentionCleaner;
import com.dawnline.fulfillment.application.port.in.CancelFulfillmentOrderUseCase;
import com.dawnline.fulfillment.application.port.in.PlanOrderUseCase;
import com.dawnline.fulfillment.application.port.out.FcDistances;
import com.dawnline.fulfillment.application.port.out.FulfillmentEvents;
import com.dawnline.fulfillment.domain.FcSelection;
import com.dawnline.fulfillment.application.port.out.FulfillmentOrderRepository;
import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.fulfillment.application.port.out.WaveRepository;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.outbox.OutboxAppender;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 유스케이스 배선 (DESIGN.md §5.2).
 *
 * <p>도메인·유스케이스 클래스에는 Spring 어노테이션이 없다(불변규칙 5). 그래서 배선이 여기 모여
 * 있고, 무엇이 무엇에 의존하는지가 한 화면에 보인다.
 *
 * <p>{@code @EnableScheduling} 은 {@code libs/messaging} 의 릴레이 자동설정도 선언하지만, 그것은
 * 릴레이가 켜졌을 때만이다. 보존 정리는 릴레이와 독립이므로 여기서도 선언한다 — 중복 선언은
 * 문제가 되지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FulfillmentProperties.class)
@EnableScheduling
public class FulfillmentApplicationConfig {

    /**
     * FC 선택의 순수 함수 (§5.2 1~6단계, ADR-021).
     *
     * @param clock      시각 출처 (불변규칙 12)
     * @param properties {@code dawnline.fulfillment.wave.stale-placed-after}
     */
    @Bean
    public com.dawnline.fulfillment.domain.FcSelection fcSelection(Clock clock,
            FulfillmentProperties properties) {
        return new com.dawnline.fulfillment.domain.FcSelection(clock, properties.wave().stalePlacedAfter());
    }

    /**
     * 판정에 넘길 후보 조립 — 카탈로그 + 거리 + 재고.
     *
     * @param referenceData 참조 데이터
     * @param distances     캠프 기준 거리
     */
    @Bean
    public FcCandidateAssembler fcCandidateAssembler(ReferenceData referenceData, FcDistances distances) {
        return new FcCandidateAssembler(referenceData, distances);
    }

    /**
     * §2.2 컷오프·배송창 표 — order-service 와 <strong>같은 구현</strong>이다
     * (ADR-020 후속 정정 2). 개정 경로가 다음 컷오프와 그 창을 물을 때 쓴다.
     */
    @Bean
    public TierSchedule tierSchedule() {
        return TierSchedule.standard();
    }

    /**
     * {@code fulfillment.planned} 발행 — outbox 뿐이다 (불변규칙 1).
     *
     * @param outbox 이벤트 발행의 유일한 진입점
     */
    @Bean
    public FulfillmentEvents fulfillmentEvents(OutboxAppender outbox) {
        return new OutboxFulfillmentEvents(outbox);
    }

    /**
     * {@code order.placed} 처리 (§5.2).
     *
     * @param referenceData 권역·캠프 조회
     * @param candidates    후보 조립
     * @param selection     판정 순수 함수
     * @param waves         웨이브 저장소
     * @param orders        주문 저장소
     * @param events        outbox 발행
     * @param schedule      §2.2 표
     * @param ids           UUIDv7 생성기
     * @param clock         시각 출처
     */
    @Bean
    public PlanOrderUseCase planOrderUseCase(ReferenceData referenceData, FcCandidateAssembler candidates,
            FcSelection selection, WaveRepository waves, FulfillmentOrderRepository orders,
            FulfillmentEvents events, TierSchedule schedule, Ids ids, Clock clock) {
        return new PlanOrderService(referenceData, candidates, selection, waves, orders, events,
                schedule, ids, clock);
    }

    /**
     * {@code order.cancelled} 처리 (ADR-022).
     *
     * @param orders 주문 저장소
     */
    @Bean
    public CancelFulfillmentOrderUseCase cancelFulfillmentOrderUseCase(FulfillmentOrderRepository orders) {
        return new CancelFulfillmentOrderService(orders);
    }

    /**
     * 주문 이벤트 리스너 (§4.1).
     *
     * @param consumer    멱등 게이트
     * @param planOrder   계획 유스케이스
     * @param cancelOrder 취소 유스케이스
     * @param json        이벤트 JSON 코덱
     * @param meters      Micrometer 레지스트리
     */
    @Bean
    public OrderEventListener orderEventListener(IdempotentConsumer consumer, PlanOrderUseCase planOrder,
            CancelFulfillmentOrderUseCase cancelOrder, EventJson json, MeterRegistry meters) {
        return new OrderEventListener(consumer, planOrder, cancelOrder, json, meters);
    }

    /**
     * 보존 정리 (ADR-023). {@code dawnline.fulfillment.retention.enabled=false} 로 끌 수 있다.
     *
     * <p>{@code Clock} 은 {@code libs/messaging} 이 저장 정밀도(마이크로초)로 자른 빈을 준다
     * (불변규칙 12).
     *
     * @param orders             주문 저장소
     * @param waves              웨이브 저장소
     * @param transactionManager 배치마다 트랜잭션을 여는 데 쓴다
     * @param clock              기준 시각
     * @param properties         {@code dawnline.fulfillment.retention.*}
     */
    @Bean
    @ConditionalOnProperty(prefix = "dawnline.fulfillment.retention", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public FulfillmentRetentionCleaner fulfillmentRetentionCleaner(FulfillmentOrderRepository orders,
            WaveRepository waves, PlatformTransactionManager transactionManager, Clock clock,
            FulfillmentProperties properties) {

        FulfillmentProperties.Retention retention = properties.retention();
        return new FulfillmentRetentionCleaner(orders, waves, transactionManager, clock,
                retention.orders(), retention.waves(), retention.batchSize(), retention.maxBatchesPerRun());
    }
}
