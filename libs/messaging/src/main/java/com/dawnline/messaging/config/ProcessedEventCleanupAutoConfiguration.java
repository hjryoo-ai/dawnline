package com.dawnline.messaging.config;

import com.dawnline.messaging.idempotency.ProcessedEventCleaner;
import com.dawnline.messaging.idempotency.ProcessedEventRepository;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code processed_events} 보존 정리 배선 (DESIGN.md §4.4, §7.1).
 *
 * <p>outbox 릴레이({@link OutboxRelayAutoConfiguration})와 <strong>따로</strong> 둔다. 정리는 소비 측
 * 관심사이고, 릴레이는 발행 측 관심사다. 릴레이 배선은 {@code KafkaOperations} 빈을 요구하지만
 * 이 스케줄러는 DB 만 있으면 된다 — 발행을 끈 서비스도 소비를 하면 이 테이블은 계속 자란다.
 *
 * <p>{@code dawnline.messaging.processed-events.enabled=false} 로 끌 수 있다. 정리 주체를 애플리케이션
 * 밖(운영 배치·pg_cron)에 두는 배포에서 쓴다.
 *
 * <p>스케줄러 풀을 릴레이와 공유한다. 일 1회 작업이라 경합은 사실상 없지만, 릴레이를 켠 서비스에
 * {@code spring.task.scheduling.pool.size} 를 2 이상 두라는 권고는 그대로다
 * ({@code OutboxRelay} Javadoc 참고).
 */
@AutoConfiguration(after = MessagingJpaAutoConfiguration.class)
@ConditionalOnProperty(prefix = "dawnline.messaging.processed-events", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@ConditionalOnBean({ProcessedEventRepository.class, PlatformTransactionManager.class})
@EnableScheduling
public class ProcessedEventCleanupAutoConfiguration {

    /**
     * 보존 정리 스케줄러.
     *
     * @param repository         {@code processed_events} 저장소
     * @param transactionManager 배치마다 새 트랜잭션을 여는 데 쓴다
     * @param clock              임계 시각 계산 (불변규칙 12)
     * @param properties         {@code dawnline.messaging.*}
     */
    @Bean
    @ConditionalOnMissingBean
    public ProcessedEventCleaner dawnlineProcessedEventCleaner(ProcessedEventRepository repository,
            PlatformTransactionManager transactionManager, ObjectProvider<Clock> clock,
            DawnlineMessagingProperties properties) {
        DawnlineMessagingProperties.ProcessedEvents config = properties.processedEvents();
        return new ProcessedEventCleaner(repository, transactionManager, clock.getIfAvailable(Clock::systemUTC),
                config.retention(), config.batchSize(), config.maxBatchesPerRun());
    }
}
