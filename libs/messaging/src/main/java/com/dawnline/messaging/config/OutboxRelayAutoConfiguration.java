package com.dawnline.messaging.config;

import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.kafka.KafkaRecordPublisher;
import com.dawnline.messaging.outbox.OutboxBatchPublisher;
import com.dawnline.messaging.outbox.OutboxMetrics;
import com.dawnline.messaging.outbox.OutboxRelay;
import com.dawnline.messaging.outbox.OutboxRepository;
import com.dawnline.messaging.outbox.RecordPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * outbox 릴레이 배선 (DESIGN.md §4.4).
 *
 * <p>{@code dawnline.messaging.outbox.enabled=false} 로 끌 수 있다. 끄면 {@code OutboxAppender} 는
 * 그대로 살아 있고(이벤트 기록은 계속된다) 발행만 멈춘다. 릴레이를 별도 프로세스로 떼어내거나,
 * 테스트에서 발행 시점을 수동으로 제어하고 싶을 때 쓴다.
 *
 * <p>{@code @EnableScheduling} 을 여기에 둔다. 릴레이가 켜졌을 때만 스케줄링이 활성화되고,
 * 애플리케이션이 이미 {@code @EnableScheduling} 을 선언했다면 중복 등록되지 않는다
 * (Spring 이 {@code SchedulingConfiguration} 을 빈 이름 기준으로 한 번만 등록한다).
 */
@AutoConfiguration(after = {MessagingJpaAutoConfiguration.class, KafkaAutoConfiguration.class})
@ConditionalOnProperty(prefix = "dawnline.messaging.outbox", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@ConditionalOnBean({OutboxRepository.class, KafkaOperations.class, PlatformTransactionManager.class})
@EnableScheduling
public class OutboxRelayAutoConfiguration {

    /**
     * outbox 게이지 (§9.1).
     *
     * @param meters      Micrometer 레지스트리
     * @param properties  {@code dawnline.messaging.*}
     * @param environment {@code spring.application.name} 조회용
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxMetrics dawnlineOutboxMetrics(ObjectProvider<MeterRegistry> meters,
            DawnlineMessagingProperties properties, Environment environment) {
        return new OutboxMetrics(meters.getIfAvailable(SimpleMeterRegistry::new),
                MessagingAutoConfiguration.resolveProducer(properties, environment));
    }

    /**
     * 릴레이의 전송 어댑터.
     *
     * <p>{@code KafkaOperations<?, ?>} 로 주입받는 이유: Boot 의 {@code KafkaAutoConfiguration} 이
     * 등록하는 빈의 선언 타입이 {@code KafkaTemplate<?, ?>} 라, 와일드카드로 받는 것이 제네릭 매칭에서
     * 가장 확실하다. 실제 직렬화기는 key·value 모두 {@code StringSerializer}(Boot 기본값)이고
     * 우리는 봉투 JSON 문자열을 보내므로 {@code <String, String>} 으로 좁히는 것이 맞다.
     * {@code spring.kafka.producer.value-serializer} 를 바꾸면 이 전제가 깨진다.
     *
     * @param kafkaOperations Kafka 템플릿
     */
    @Bean
    @ConditionalOnMissingBean
    public RecordPublisher dawnlineRecordPublisher(KafkaOperations<?, ?> kafkaOperations) {
        @SuppressWarnings("unchecked")
        KafkaOperations<String, String> typed = (KafkaOperations<String, String>) kafkaOperations;
        return new KafkaRecordPublisher(typed);
    }

    /**
     * 배치 발행기.
     *
     * @param repository         outbox 저장소
     * @param publisher          전송 포트
     * @param json               이벤트 JSON 코덱
     * @param transactionManager 배치 트랜잭션
     * @param clock              시각 출처
     * @param properties         {@code dawnline.messaging.*}
     * @param environment        {@code spring.application.name} 조회용
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxBatchPublisher dawnlineOutboxBatchPublisher(OutboxRepository repository, RecordPublisher publisher,
            EventJson json, PlatformTransactionManager transactionManager, ObjectProvider<Clock> clock,
            DawnlineMessagingProperties properties, Environment environment) {
        return new OutboxBatchPublisher(repository, publisher, json, new TransactionTemplate(transactionManager),
                clock.getIfAvailable(MessagingAutoConfiguration::storagePrecisionClock),
                MessagingAutoConfiguration.resolveProducer(properties, environment),
                properties.outbox().batchSize(), properties.outbox().sendTimeout());
    }

    /**
     * 폴링 릴레이.
     *
     * @param publisher          배치 발행기
     * @param repository         outbox 저장소
     * @param metrics            게이지
     * @param transactionManager 유지보수 트랜잭션
     * @param clock              시각 출처
     * @param properties         {@code dawnline.messaging.*}
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxRelay dawnlineOutboxRelay(OutboxBatchPublisher publisher, OutboxRepository repository,
            OutboxMetrics metrics, PlatformTransactionManager transactionManager, ObjectProvider<Clock> clock,
            DawnlineMessagingProperties properties) {
        return new OutboxRelay(publisher, repository, metrics, transactionManager,
                clock.getIfAvailable(MessagingAutoConfiguration::storagePrecisionClock), properties.outbox().retention());
    }
}
