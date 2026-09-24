package com.dawnline.ops.config;

import com.dawnline.ops.adapter.in.messaging.ProjectionListener;
import com.dawnline.ops.adapter.out.messaging.KafkaDeadLetters;
import com.dawnline.ops.application.DlqReplayService;
import com.dawnline.ops.application.port.in.ReplayDeadLettersUseCase;
import com.dawnline.ops.application.port.out.AuditLog;
import com.dawnline.ops.application.port.out.DeadLetters;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * DLQ 재처리 배선 (DESIGN.md §4.6 「DLQ 재처리」, ADR-053).
 *
 * <p>{@code byte[]} 프로듀서·컨슈머는 <strong>빈이 아니다</strong> — 어댑터 안에서만 산다. {@code KafkaTemplate}·
 * {@code ProducerFactory}·{@code ConsumerFactory} 빈을 하나 더 두면 Boot 의 것이 물러나고
 * ({@code @ConditionalOnMissingBean}) 리스너·DLQ 복구기가 그것을 잃는다. 설정은 Boot 의 {@code spring.kafka.*} 에서
 * 빌려 오고(주소·보안·acks·멱등 프로듀서) 필요한 것만 덮는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpsDlqProperties.class)
public class DlqReplayConfig {

    /**
     * @param kafka      Boot 의 {@code spring.kafka.*}
     * @param properties 시간 상한
     * @param json       {@code eventId} 를 읽는 데만 쓴다
     * @return DLQ 어댑터 — 닫을 때 프로듀서를 닫는다
     */
    @Bean
    public DeadLetters deadLetters(KafkaProperties kafka, OpsDlqProperties properties, JsonMapper json) {
        Map<String, Object> consumer = new HashMap<>(kafka.buildConsumerProperties());
        // 그룹 없이 assign 으로만 읽는다 — ops-api 의 프로젝션 그룹(ops-api)에 끼어들면 파티션을 나눠 갖는다.
        consumer.remove(ConsumerConfig.GROUP_ID_CONFIG);
        consumer.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumer.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);

        Map<String, Object> producer = new HashMap<>(kafka.buildProducerProperties());
        int send = Math.toIntExact(properties.sendTimeout().toMillis());
        // ack 를 기다리는 시간과 프로듀서가 스스로 재시도하는 시간을 같게 둔다 — 기다림이 끝난 뒤 프로듀서가 뒤늦게
        // 보내면 UNKNOWN 이 「안 갔다」로 굳을 기회를 잃는다. request.timeout 은 delivery.timeout 보다 짧아야 한다.
        producer.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, send);
        producer.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, send / 2);
        producer.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        producer.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, send);

        return new KafkaDeadLetters(
                new DefaultKafkaConsumerFactory<>(consumer, new ByteArrayDeserializer(), new ByteArrayDeserializer()),
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producer, new ByteArraySerializer(),
                        new ByteArraySerializer())),
                json, properties.readTimeout(), properties.sendTimeout());
    }

    /**
     * @param letters  DLQ
     * @param audit    {@code audit_logs}
     * @param clock    저장 정밀도로 자른 시계
     * @param registry 카운터 레지스트리
     * @return 재처리 유스케이스 — 받는 토픽은 프로젝션이 구독하는 토픽 전부(= 계약의 토픽 전부)
     */
    @Bean
    public ReplayDeadLettersUseCase replayDeadLetters(DeadLetters letters, AuditLog audit, Clock clock,
            MeterRegistry registry) {
        return new DlqReplayService(letters, audit, ProjectionListener.TOPICS, clock, registry);
    }
}
