package com.dawnline.tracking.config;

import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.tracking.adapter.in.messaging.RouteAssignedListener;
import com.dawnline.tracking.adapter.out.persistence.JdbcEventPartitions;
import com.dawnline.tracking.adapter.out.persistence.JdbcRouteRevisions;
import com.dawnline.tracking.adapter.out.persistence.JpaShipmentRepository;
import com.dawnline.tracking.application.ApplyRouteAssignmentService;
import com.dawnline.tracking.application.ShipmentEventPartitions;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase;
import com.dawnline.tracking.application.port.out.EventPartitions;
import com.dawnline.tracking.application.port.out.RouteRevisions;
import com.dawnline.tracking.application.port.out.ShipmentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 유스케이스 배선 (DESIGN.md §5.4).
 *
 * <p>애플리케이션·도메인 클래스에는 Spring 어노테이션이 없다(불변규칙 5). 배선이 여기 모여
 * 있어서 무엇이 무엇에 의존하는지가 한 화면에 보인다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TrackingProperties.class)
@EnableScheduling
public class TrackingApplicationConfig {

    // --- route.assigned 소비 (§5.4, §8.5) ------------------------------------

    /**
     * {@code shipments} 포트의 JPA 구현.
     *
     * @param entityManagerFactory 이 서비스의 EMF. 공유 프록시를 만들어 넘긴다 —
     *                             트랜잭션마다 올바른 EntityManager 가 물린다
     */
    @Bean
    public ShipmentRepository shipmentRepository(EntityManagerFactory entityManagerFactory) {
        return new JpaShipmentRepository(
                SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
    }

    /**
     * {@code route_revisions} 포트 (ADR-045).
     *
     * @param jdbc 같은 트랜잭션에 참여하는 JDBC 템플릿
     */
    @Bean
    public RouteRevisions routeRevisions(JdbcTemplate jdbc) {
        return new JdbcRouteRevisions(jdbc);
    }

    /**
     * 개정 반영 유스케이스 (§5.4).
     *
     * @param shipments 배송 저장소
     * @param revisions 개정 저장소
     * @param clock     {@code applied_at} 시각 출처 (불변규칙 12)
     */
    @Bean
    public ApplyRouteAssignmentUseCase applyRouteAssignmentUseCase(ShipmentRepository shipments,
            RouteRevisions revisions, Clock clock) {
        return new ApplyRouteAssignmentService(shipments, revisions, clock);
    }

    /**
     * {@code route.assigned} 리스너 (§4.1).
     *
     * @param consumer             멱등 게이트
     * @param applyRouteAssignment 개정 반영 유스케이스
     * @param json                 이벤트 JSON 코덱
     * @param meters               Micrometer 레지스트리
     */
    @Bean
    public RouteAssignedListener routeAssignedListener(IdempotentConsumer consumer,
            ApplyRouteAssignmentUseCase applyRouteAssignment, EventJson json, MeterRegistry meters) {
        return new RouteAssignedListener(consumer, applyRouteAssignment, json, meters);
    }

    // --- shipment_events 일 파티션 (§5.4) -------------------------------------

    /**
     * 파티션 포트의 PostgreSQL 구현.
     *
     * @param jdbc 이 서비스의 데이터소스
     */
    @Bean
    public EventPartitions eventPartitions(JdbcTemplate jdbc) {
        return new JdbcEventPartitions(jdbc);
    }

    /**
     * 일 파티션 관리 (§5.4).
     *
     * <p>{@code enabled=false} 면 빈이 없고 스케줄도 없다 — 통합 테스트가 생성 시점을 직접
     * 정하려고 끈다. 운영에서 끄면 며칠 뒤 스캔 INSERT 가 통째로 실패한다.
     *
     * @param partitions 파티션 포트
     * @param clock      오늘을 읽는 시계 (불변규칙 12)
     * @param properties {@code dawnline.tracking.partitions.*}
     */
    @Bean
    @ConditionalOnProperty(prefix = "dawnline.tracking.partitions", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public ShipmentEventPartitions shipmentEventPartitions(EventPartitions partitions, Clock clock,
            TrackingProperties properties) {
        return new ShipmentEventPartitions(partitions, clock,
                properties.partitions().aheadDays(), properties.partitions().retentionDays());
    }

    /**
     * {@code dawnline_shipment_partitions_ahead} (§9.1) — 앞으로 덮여 있는 날 수.
     *
     * <p>게이지는 <em>남은 날</em>을 잰다. 「마지막 실행에서 만든 수」로 재면 스케줄러가 멈췄을 때
     * 값이 그대로 멈춰 있어 건강해 보인다.
     *
     * @param registry   미터 레지스트리
     * @param partitions 파티션 관리자
     */
    @Bean
    @ConditionalOnProperty(prefix = "dawnline.tracking.partitions", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public Gauge shipmentPartitionsAheadGauge(MeterRegistry registry, ShipmentEventPartitions partitions) {
        return Gauge.builder("dawnline_shipment_partitions_ahead", partitions,
                        ShipmentEventPartitions::partitionsAhead)
                .description("오늘을 포함해 앞으로 덮여 있는 shipment_events 일 파티션 수 (DESIGN.md §5.4)")
                .register(registry);
    }
}
