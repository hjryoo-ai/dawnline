package com.dawnline.ops.config;

import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.ops.adapter.in.messaging.ProjectionListener;
import com.dawnline.ops.adapter.out.persistence.JdbcOrderRows;
import com.dawnline.ops.adapter.out.persistence.JdbcRouteRows;
import com.dawnline.ops.adapter.out.persistence.JdbcWaveRows;
import com.dawnline.ops.application.ReadModelProjector;
import com.dawnline.ops.application.port.in.ProjectFactUseCase;
import com.dawnline.ops.application.port.out.OrderRows;
import com.dawnline.ops.application.port.out.RouteRows;
import com.dawnline.ops.application.port.out.WaveRows;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 유스케이스 배선 (DESIGN.md §5.5).
 *
 * <p>애플리케이션·도메인 클래스에는 Spring 어노테이션이 없다(불변규칙 5). 배선이 여기 모여
 * 있어서 무엇이 무엇에 의존하는지가 한 화면에 보인다.
 */
@Configuration(proxyBeanMethods = false)
public class OpsApplicationConfig {

    // --- 읽기 모델 프로젝션 (§5.5, ADR-051) ------------------------------------

    /**
     * @param jdbc 같은 트랜잭션에 참여하는 JDBC 템플릿
     * @return {@code rm_orders}
     */
    @Bean
    public OrderRows orderRows(JdbcTemplate jdbc) {
        return new JdbcOrderRows(jdbc);
    }

    /**
     * @param jdbc 같은 트랜잭션에 참여하는 JDBC 템플릿
     * @return {@code rm_routes}
     */
    @Bean
    public RouteRows routeRows(JdbcTemplate jdbc) {
        return new JdbcRouteRows(jdbc);
    }

    /**
     * @param jdbc 같은 트랜잭션에 참여하는 JDBC 템플릿
     * @return {@code rm_waves}
     */
    @Bean
    public WaveRows waveRows(JdbcTemplate jdbc) {
        return new JdbcWaveRows(jdbc);
    }

    /**
     * @param orders {@code rm_orders}
     * @param routes {@code rm_routes}
     * @param waves  {@code rm_waves}
     * @param clock  {@code updated_at} 시각 출처 (불변규칙 12)
     * @return 프로젝션 유스케이스
     */
    @Bean
    public ProjectFactUseCase projectFactUseCase(OrderRows orders, RouteRows routes, WaveRows waves,
            Clock clock) {
        return new ReadModelProjector(orders, routes, waves, clock);
    }

    /**
     * §4.1 의 토픽 전부를 받는 리스너.
     *
     * @param consumer  멱등 게이트
     * @param projector 프로젝션 유스케이스
     * @param json      이벤트 JSON 코덱
     * @param meters    Micrometer 레지스트리
     * @return 리스너
     */
    @Bean
    public ProjectionListener projectionListener(IdempotentConsumer consumer, ProjectFactUseCase projector,
            EventJson json, MeterRegistry meters) {
        return new ProjectionListener(consumer, projector, json, meters);
    }
}
