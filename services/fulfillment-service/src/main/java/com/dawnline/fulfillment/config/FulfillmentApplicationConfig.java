package com.dawnline.fulfillment.config;

import com.dawnline.fulfillment.application.FulfillmentRetentionCleaner;
import com.dawnline.fulfillment.application.port.out.FulfillmentOrderRepository;
import com.dawnline.fulfillment.application.port.out.WaveRepository;
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
