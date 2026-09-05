package com.dawnline.messaging.config;

import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.kafka.KafkaRecordPublisher;
import com.dawnline.messaging.outbox.OutboxBatchPublisher;
import com.dawnline.messaging.outbox.OutboxMetrics;
import com.dawnline.messaging.outbox.OutboxRelay;
import com.dawnline.messaging.outbox.OutboxRepository;
import com.dawnline.messaging.outbox.RecordPublisher;
import com.dawnline.messaging.outbox.RelayLeadership;
import com.dawnline.messaging.redis.RedisRelayLeadership;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    /** 락을 켜 뒀는데 조정자가 없을 때의 기동 실패 메시지. 두 자리에서 같은 문장을 쓴다. */
    static final String MISSING_LEADER_COORDINATOR =
            "릴레이 리더 락이 켜져 있는데 StringRedisTemplate 이 없습니다. "
                    + "Redis 를 붙이거나, 인스턴스가 하나임을 "
                    + "dawnline.messaging.outbox.leader.enabled=false 로 적어 주세요 "
                    + "(DESIGN.md §4.4, ADR-027).";

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
     * 리더 락이 <strong>꺼져 있을 때</strong>, 또는 Redis 가 클래스패스에 아예 없을 때의 배선.
     *
     * <p>꺼져 있으면 "이 배포는 인스턴스가 하나다" 라는 선언이다. 켜져 있는데 여기까지 오면 Redis 를
     * 쓸 수 없다는 뜻이고, 그때는 <strong>기동에서 실패한다.</strong> 조용히 항상-리더로 떨어뜨리지
     * 않는 이유: 없는 락을 있다고 믿는 것이 락이 없는 것보다 나쁘다. 스케일아웃한 날 아무 신호 없이
     * §4.5 가 깨지고, 그때 깨진 것은 로그가 아니라 이벤트 순서다.
     *
     * <p>{@link RedisRelayLeadershipConfiguration} 은 중첩 클래스라 이 메서드보다 <em>먼저</em>
     * 등록된다(스프링은 멤버 클래스를 바깥 {@code @Bean} 보다 먼저 처리한다). 그래서 Redis 가 있으면
     * {@code @ConditionalOnMissingBean} 이 여기를 건너뛴다.
     *
     * @param properties {@code dawnline.messaging.*}
     */
    @Bean
    @ConditionalOnMissingBean(RelayLeadership.class)
    public RelayLeadership dawnlineRelayLeadership(DawnlineMessagingProperties properties) {
        if (properties.outbox().leader().enabled()) {
            throw new IllegalStateException(MISSING_LEADER_COORDINATOR);
        }
        return RelayLeadership.singleInstance();
    }

    /**
     * Redis 리더 락 (§7.2 {@code lock:relay:{service}}, ADR-027).
     *
     * <p>{@code @ConditionalOnClass} 를 타입 자체에 건다 — Redis 를 쓰지 않는 서비스(ops-api)의
     * 클래스패스에는 {@code StringRedisTemplate} 이 아예 없고, 그 경우 이 클래스는 로드되지 않는다.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "dawnline.messaging.outbox.leader", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public static class RedisRelayLeadershipConfiguration {

        /**
         * 템플릿을 {@code ObjectProvider} 로 받는다 — <strong>{@code @ConditionalOnBean} 이
         * 아니다.</strong>
         *
         * <p>조건은 <em>빈 정의가 등록되는 순서</em>에 의존한다. 이 자동설정은
         * {@code RedisAutoConfiguration} 보다 먼저 평가될 수 있고, 그러면 템플릿이 곧 생길
         * 것인데도 "없다" 로 판정해 바깥의 기동 실패로 떨어진다(2026-09-05에 실제로 그랬다).
         * {@code ObjectProvider} 는 <em>빈을 만드는 시점</em>에 찾으므로 그 순서에서 자유롭다.
         *
         * @param redis       문자열 전용 템플릿 제공자
         * @param properties  {@code dawnline.messaging.*}
         * @param environment {@code spring.application.name} 조회용
         */
        @Bean
        @ConditionalOnMissingBean(RelayLeadership.class)
        public RelayLeadership dawnlineRedisRelayLeadership(ObjectProvider<StringRedisTemplate> redis,
                DawnlineMessagingProperties properties, Environment environment) {
            StringRedisTemplate template = redis.getIfAvailable();
            if (template == null) {
                throw new IllegalStateException(MISSING_LEADER_COORDINATOR);
            }
            return new RedisRelayLeadership(template,
                    MessagingAutoConfiguration.resolveProducer(properties, environment),
                    properties.outbox().leader().ttl());
        }
    }
}
