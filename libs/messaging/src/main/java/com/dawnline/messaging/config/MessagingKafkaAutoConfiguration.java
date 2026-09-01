package com.dawnline.messaging.config;

import com.dawnline.messaging.Topics;
import com.dawnline.messaging.kafka.DawnlineErrorHandlers;
import com.dawnline.messaging.kafka.DlqRecordRecoverer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

/**
 * Kafka 소비 쪽 공통 배선 (DESIGN.md §4.6 재시도/DLQ, §8.3 백프레셔).
 *
 * <p>리스너 컨테이너 팩토리를 직접 만들지 않는다. Boot 4 의 {@code KafkaAnnotationDrivenConfiguration} 이
 * {@code ObjectProvider<CommonErrorHandler>} 로 에러 핸들러 빈을 찾아 기본
 * {@code kafkaListenerContainerFactory} 에 적용해 주기 때문이다. 팩토리를 새로 만들면 Boot 의
 * {@code spring.kafka.listener.*} 설정이 통째로 무시되는 부작용이 생긴다.
 * 우리가 할 일은 <strong>핸들러 빈 하나를 놓는 것</strong>뿐이다.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
public class MessagingKafkaAutoConfiguration {

    /**
     * DLQ 발행기 — {@code <topic>.dlq} 로 보낸다 (§4.6).
     *
     * <p>파티션은 원본과 같은 번호를 쓴다(Spring Kafka 기본 동작과 동일). DLQ 토픽의 파티션 수가 더 적으면
     * {@link DeadLetterPublishingRecoverer} 가 {@code verifyPartition} 으로 감지해 파티션 지정을 포기하고
     * 키 해시로 보낸다. compose 의 토픽 생성 스크립트는 원본과 DLQ 를 같은 파티션 수로 만든다 (§7.3).
     *
     * @param kafkaOperations Kafka 발행 (Boot 가 등록한 {@code KafkaTemplate<?, ?>})
     * @param meters          Micrometer 레지스트리
     * @param properties      {@code dawnline.messaging.*}
     * @param environment     {@code spring.application.name} 조회용
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(KafkaOperations.class)
    @ConditionalOnProperty(prefix = "dawnline.messaging.retry", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public DlqRecordRecoverer dawnlineDlqRecordRecoverer(KafkaOperations<?, ?> kafkaOperations,
            ObjectProvider<MeterRegistry> meters, DawnlineMessagingProperties properties, Environment environment) {
        String dlqSuffix = properties.retry().dlqSuffix();
        DeadLetterPublishingRecoverer delegate = new DeadLetterPublishingRecoverer(kafkaOperations,
                (record, exception) -> new TopicPartition(dlqTopic(record.topic(), dlqSuffix), record.partition()));
        return new DlqRecordRecoverer(delegate, meters.getIfAvailable(SimpleMeterRegistry::new),
                MessagingAutoConfiguration.resolveProducer(properties, environment));
    }

    /**
     * §4.6 의 재시도 → DLQ 핸들러. Boot 의 기본 리스너 컨테이너 팩토리가 이 빈을 집어 간다.
     *
     * @param recoverer  DLQ 발행기
     * @param properties {@code dawnline.messaging.*}
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    @ConditionalOnBean(DlqRecordRecoverer.class)
    public CommonErrorHandler dawnlineKafkaErrorHandler(DlqRecordRecoverer recoverer,
            DawnlineMessagingProperties properties) {
        return DawnlineErrorHandlers.retryThenDlq(recoverer, properties.retry());
    }

    /**
     * 백프레셔 기본값 {@code max.poll.records=100} (§8.3).
     *
     * <p>{@code spring.kafka.consumer.max-poll-records} 를 명시하면 그 값이 그대로 팩토리 설정에 들어가고,
     * 여기서 다시 덮어쓰면 사용자의 명시적 설정이 지워진다. 그래서 <strong>이미 설정돼 있으면 건드리지 않는다</strong>.
     *
     * @param properties  {@code dawnline.messaging.*}
     * @param environment {@code spring.kafka.consumer.max-poll-records} 존재 확인용
     */
    @Bean
    @ConditionalOnProperty(prefix = "dawnline.messaging.consumer", name = "apply-max-poll-records",
            havingValue = "true", matchIfMissing = true)
    public DefaultKafkaConsumerFactoryCustomizer dawnlineConsumerBackpressureCustomizer(
            DawnlineMessagingProperties properties, Environment environment) {
        int maxPollRecords = properties.consumer().maxPollRecords();
        boolean explicitlyConfigured = environment.containsProperty("spring.kafka.consumer.max-poll-records");
        return factory -> {
            if (!explicitlyConfigured) {
                factory.updateConfigs(Map.of(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords));
            }
        };
    }

    /** 이미 DLQ 인 토픽에 접미사를 또 붙이지 않는다 (무한 {@code .dlq.dlq} 방지). */
    private static String dlqTopic(String topic, String suffix) {
        if (Topics.DLQ_SUFFIX.equals(suffix)) {
            return Topics.dlqFor(topic);
        }
        return topic.endsWith(suffix) ? topic : topic + suffix;
    }
}
