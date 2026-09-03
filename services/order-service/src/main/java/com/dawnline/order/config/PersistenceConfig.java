package com.dawnline.order.config;

import com.dawnline.order.adapter.out.persistence.JpaIdempotencyRecords;
import com.dawnline.order.adapter.out.persistence.JpaOrderRepository;
import com.dawnline.order.application.port.out.IdempotencyRecords;
import com.dawnline.order.application.port.out.OrderRepository;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import tools.jackson.databind.ObjectMapper;

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

    /**
     * {@code idempotency_keys} 저장소 (§5.1, ADR-018).
     *
     * <p>{@code ObjectMapper} 는 웹 어댑터가 응답을 쓸 때와 <strong>같은</strong> 빈이어야 한다 —
     * {@code response_body} 는 그 응답을 그대로 재생하기 위한 값이기 때문이다.
     *
     * @param entityManagerFactory EMF
     * @param json                 애플리케이션 JSON 매퍼
     */
    @Bean
    public IdempotencyRecords idempotencyRecords(EntityManagerFactory entityManagerFactory, ObjectMapper json) {
        return new JpaIdempotencyRecords(
                SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory), json);
    }
}
