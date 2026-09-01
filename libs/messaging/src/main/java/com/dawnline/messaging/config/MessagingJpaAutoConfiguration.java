package com.dawnline.messaging.config;

import com.dawnline.common.Ids;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.idempotency.JpaProcessedEventRepository;
import com.dawnline.messaging.idempotency.ProcessedEventRepository;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.outbox.JpaOutboxRepository;
import com.dawnline.messaging.outbox.OutboxAppender;
import com.dawnline.messaging.outbox.OutboxRepository;
import com.dawnline.messaging.outbox.TraceparentSupplier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * outbox·멱등 소비의 JPA 배선 (DESIGN.md §4.4, §5.1).
 *
 * <p>{@link MessagingEntityPackagesRegistrar} 가 {@code com.dawnline.messaging} 을 엔티티 스캔에
 * 더해 주므로, 서비스는 {@code @EntityScan} 을 손댈 필요가 없다.
 *
 * <p>Spring Data 리포지토리를 쓰지 않는다. 이유는 {@link JpaOutboxRepository} Javadoc 참고
 * (생성되는 SQL 을 눈으로 확인할 수 있어야 하고, 라이브러리가 애플리케이션에 스캔 설정을 강요하지 않는다).
 */
@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass({EntityManagerFactory.class, PlatformTransactionManager.class})
@ConditionalOnBean(EntityManagerFactory.class)
@Import(MessagingEntityPackagesRegistrar.class)
public class MessagingJpaAutoConfiguration {

    /**
     * {@code outbox_events} 저장소.
     *
     * <p>{@link SharedEntityManagerCreator} 가 만드는 프록시는 {@code @PersistenceContext} 가 주입하는
     * 것과 같은 물건이다 — 현재 트랜잭션의 EntityManager 로 위임하고, 트랜잭션이 없으면 임시로 하나 연다.
     *
     * @param entityManagerFactory EMF
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxRepository dawnlineOutboxRepository(EntityManagerFactory entityManagerFactory) {
        return new JpaOutboxRepository(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
    }

    /**
     * {@code processed_events} 저장소.
     *
     * @param entityManagerFactory EMF
     */
    @Bean
    @ConditionalOnMissingBean
    public ProcessedEventRepository dawnlineProcessedEventRepository(EntityManagerFactory entityManagerFactory) {
        return new JpaProcessedEventRepository(
                SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
    }

    /**
     * 이벤트 발행의 유일한 진입점 (CLAUDE.md 불변규칙 1).
     *
     * @param repository   outbox 저장소
     * @param json         이벤트 JSON 코덱
     * @param ids          UUIDv7 생성기
     * @param clock        사건 시각 출처
     * @param traceparents 트레이스 컨텍스트 제공자
     * @param properties   {@code dawnline.messaging.*}
     * @param environment  {@code spring.application.name} 조회용
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxAppender dawnlineOutboxAppender(OutboxRepository repository, EventJson json, Ids ids,
            ObjectProvider<Clock> clock, TraceparentSupplier traceparents, DawnlineMessagingProperties properties,
            Environment environment) {
        return new OutboxAppender(repository, json, ids, clock.getIfAvailable(Clock::systemUTC),
                MessagingAutoConfiguration.resolveProducer(properties, environment), traceparents);
    }

    /**
     * 멱등 게이트 (CLAUDE.md 불변규칙 2).
     *
     * @param repository         {@code processed_events} 저장소
     * @param transactionManager 트랜잭션 관리자
     * @param meters             Micrometer 레지스트리 (없으면 인메모리 레지스트리로 대체)
     * @param clock              시각 출처
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotentConsumer dawnlineIdempotentConsumer(ProcessedEventRepository repository,
            PlatformTransactionManager transactionManager, ObjectProvider<MeterRegistry> meters,
            ObjectProvider<Clock> clock) {
        return new IdempotentConsumer(repository, transactionManager,
                meters.getIfAvailable(SimpleMeterRegistry::new), clock.getIfAvailable(Clock::systemUTC));
    }
}
