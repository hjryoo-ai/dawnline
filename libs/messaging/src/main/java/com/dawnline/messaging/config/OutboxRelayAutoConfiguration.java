package com.dawnline.messaging.config;

import com.dawnline.messaging.jdbc.AdvisoryLockRelayLeadership;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.kafka.KafkaRecordPublisher;
import com.dawnline.messaging.outbox.OutboxBatchPublisher;
import com.dawnline.messaging.outbox.OutboxMetrics;
import com.dawnline.messaging.outbox.OutboxRelay;
import com.dawnline.messaging.outbox.OutboxRepository;
import com.dawnline.messaging.outbox.RecordPublisher;
import com.dawnline.messaging.outbox.RelayLeadership;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import javax.sql.DataSource;
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
     * @param leadership         단일 활성 인스턴스 판정 (ADR-027)
     * @param transactionManager 유지보수 트랜잭션
     * @param clock              시각 출처
     * @param properties         {@code dawnline.messaging.*}
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxRelay dawnlineOutboxRelay(OutboxBatchPublisher publisher, OutboxRepository repository,
            OutboxMetrics metrics, RelayLeadership leadership, PlatformTransactionManager transactionManager,
            ObjectProvider<Clock> clock, DawnlineMessagingProperties properties) {
        return new OutboxRelay(publisher, repository, metrics, leadership, transactionManager,
                clock.getIfAvailable(MessagingAutoConfiguration::storagePrecisionClock), properties.outbox().retention());
    }

    /**
     * 릴레이 리더십 — PostgreSQL advisory lock (§4.4, ADR-027 후속 정정).
     *
     * <p>{@code DataSource} 를 <strong>조건 없이 파라미터로 받는다.</strong> 없으면 빈 생성이
     * 실패하고, 그것이 맞는 동작이다 — 이 자동설정은 {@code OutboxRepository}(JPA)가 있을 때만
     * 도는데, JPA 가 있으면 데이터소스도 있다. 조건({@code @ConditionalOnBean})으로 걸지 않는
     * 이유는 그것이 <em>빈 정의 등록 순서</em>에 의존하기 때문이다. 2026-09-05 에 그 순서 문제로
     * 이 모듈의 통합 테스트 12개가 한꺼번에 깨졌다.
     *
     * <p><strong>탈출구가 없다.</strong> 이전 구현에는 {@code leader.enabled=false} 가 있었고
     * 그것은 "Redis 가 없는 배포" 를 위한 것이었다. 이제 조정자는 릴레이가 이미 쓰고 있는
     * 데이터소스라 없을 수가 없고, 없는 조건을 위한 탈출구는 남겨 두면 다른 이유로 쓰인다.
     *
     * @param dataSource  outbox 가 들어 있는 그 DB
     * @param properties  {@code dawnline.messaging.*}
     * @param environment {@code spring.application.name} 조회용
     */
    @Bean
    @ConditionalOnMissingBean(RelayLeadership.class)
    public RelayLeadership dawnlineRelayLeadership(DataSource dataSource,
            DawnlineMessagingProperties properties, Environment environment) {
        return new AdvisoryLockRelayLeadership(dataSource,
                MessagingAutoConfiguration.resolveProducer(properties, environment));
    }
}
