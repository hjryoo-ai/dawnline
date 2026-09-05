package com.dawnline.fulfillment.config;

import com.dawnline.fulfillment.adapter.out.persistence.JpaFulfillmentOrderRepository;
import com.dawnline.fulfillment.adapter.out.persistence.JpaWaveRepository;
import com.dawnline.fulfillment.application.port.out.FulfillmentOrderRepository;
import com.dawnline.fulfillment.application.port.out.WaveRepository;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/**
 * 영속화 어댑터 배선 (DESIGN.md §3.4, §5.2).
 *
 * <p>Spring Data 리포지토리를 쓰지 않는다. order-service 와 같은 이유다 — 생성되는 쿼리가 소스에
 * 그대로 있어야 하고, 여기서는 특히 그렇다(부분 인덱스를 타는 리터럴, {@code ON CONFLICT},
 * {@code ctid} 배치 삭제).
 */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfig {

    /**
     * {@code waves} 저장소.
     *
     * @param entityManagerFactory EMF
     */
    @Bean
    public WaveRepository waveRepository(EntityManagerFactory entityManagerFactory) {
        return new JpaWaveRepository(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
    }

    /**
     * {@code fulfillment_orders} 저장소 (ADR-022).
     *
     * @param entityManagerFactory EMF
     */
    @Bean
    public FulfillmentOrderRepository fulfillmentOrderRepository(EntityManagerFactory entityManagerFactory) {
        return new JpaFulfillmentOrderRepository(
                SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
    }
}
