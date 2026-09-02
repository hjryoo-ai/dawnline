package com.dawnline.order.config;

import com.dawnline.order.adapter.out.persistence.JpaOrderRepository;
import com.dawnline.order.application.port.out.OrderRepository;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/**
 * 영속화 어댑터 배선 (DESIGN.md §3.4, §5.1).
 *
 * <p>Spring Data 리포지토리를 쓰지 않는다. 이유는 {@code libs/messaging} 의
 * {@code JpaOutboxRepository} Javadoc 과 같다 — 생성되는 쿼리가 소스에 그대로 있어야 하고,
 * 라이브러리·어댑터가 애플리케이션에 스캔 설정을 강요하지 않는 편이 낫다.
 */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfig {

    /**
     * {@code orders} 저장소.
     *
     * <p>{@link SharedEntityManagerCreator} 가 만드는 프록시는 {@code @PersistenceContext} 가
     * 주입하는 것과 같다 — 현재 트랜잭션의 EntityManager 로 위임한다.
     *
     * @param entityManagerFactory EMF
     */
    @Bean
    public OrderRepository orderRepository(EntityManagerFactory entityManagerFactory) {
        return new JpaOrderRepository(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
    }
}
