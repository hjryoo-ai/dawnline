package com.dawnline.ops.config;

import com.dawnline.messaging.retention.RetentionAges;
import com.dawnline.ops.adapter.out.persistence.JdbcReadModelRetention;
import com.dawnline.ops.application.ReadModelRetentionCleaner;
import com.dawnline.ops.application.port.out.ReadModelRetention;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 읽기 모델 보존 정리 배선 ([ADR-058](docs/adr/ADR-058-shipment-and-read-model-retention.md), DESIGN.md §7.1 보존 표).
 *
 * <p>{@code dawnline.ops.retention.enabled=false} 로 끌 수 있다 — 끄면 세 표가 자라기만 하고 성공 나이 게이지도,
 * 걸린 행 게이지도 없다(정리 주체를 밖에 둔 배포). 스케줄은 {@link OpsApplicationConfig} 의
 * {@code @EnableScheduling} 이 켠다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpsRetentionProperties.class)
public class ReadModelRetentionConfig {

    /**
     * @param jdbc 부르는 쪽의 트랜잭션에 참여하는 JDBC 템플릿
     * @return 보존 삭제와 셈의 포트
     */
    @Bean
    public ReadModelRetention readModelRetention(JdbcTemplate jdbc) {
        return new JdbcReadModelRetention(jdbc);
    }

    /**
     * @param retention          삭제와 셈의 포트
     * @param transactionManager 배치마다 트랜잭션을 여는 데 쓴다
     * @param clock              기준 시각 (불변규칙 12)
     * @param properties         {@code dawnline.ops.retention.*}
     * @param ages               정리의 성공 나이 게이지 (ADR-058 결정 6)
     * @param meters             {@code dawnline_rm_orders_stuck} (결정 3)
     * @return 정리기
     */
    @Bean
    @ConditionalOnProperty(prefix = "dawnline.ops.retention", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public ReadModelRetentionCleaner readModelRetentionCleaner(ReadModelRetention retention,
            PlatformTransactionManager transactionManager, Clock clock, OpsRetentionProperties properties,
            RetentionAges ages, MeterRegistry meters) {
        return new ReadModelRetentionCleaner(retention, transactionManager, clock, properties.orders(),
                properties.ordersCap(), properties.routes(), properties.waves(), properties.batchSize(),
                properties.maxBatchesPerRun(), ages, meters);
    }
}
