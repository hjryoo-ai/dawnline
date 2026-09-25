package com.dawnline.ops.config;

import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.ops.adapter.in.messaging.ProjectionListener;
import com.dawnline.ops.adapter.out.persistence.JdbcAuditLog;
import com.dawnline.ops.adapter.out.persistence.JdbcDeliveryKpis;
import com.dawnline.ops.adapter.out.persistence.JdbcOrderRows;
import com.dawnline.ops.adapter.out.persistence.JdbcReadModelViews;
import com.dawnline.ops.adapter.out.persistence.JdbcRouteCounts;
import com.dawnline.ops.adapter.out.persistence.JdbcRouteRows;
import com.dawnline.ops.adapter.out.persistence.JdbcWaveRows;
import com.dawnline.ops.application.KpiGauges;
import com.dawnline.ops.application.OpsCommandService;
import com.dawnline.ops.application.QuarantineQueryService;
import com.dawnline.ops.application.ReadModelProjector;
import com.dawnline.ops.application.ReadModelQueryService;
import com.dawnline.ops.application.port.in.ListQuarantinedOutboxUseCase;
import com.dawnline.ops.application.port.in.ProjectFactUseCase;
import com.dawnline.ops.application.port.in.QueryReadModelUseCase;
import com.dawnline.ops.application.port.in.RunOpsCommandUseCase;
import com.dawnline.ops.application.port.out.AuditLog;
import com.dawnline.ops.application.port.out.CoreCommands;
import com.dawnline.ops.application.port.out.CoreQueries;
import com.dawnline.ops.application.port.out.DeliveryKpis;
import com.dawnline.ops.application.port.out.OrderRows;
import com.dawnline.ops.application.port.out.ReadModelViews;
import com.dawnline.ops.application.port.out.RouteCounts;
import com.dawnline.ops.application.port.out.RouteRows;
import com.dawnline.ops.application.port.out.WaveRows;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

/**
 * 유스케이스 배선 (DESIGN.md §5.5).
 *
 * <p>애플리케이션·도메인 클래스에는 Spring 어노테이션이 없다(불변규칙 5). 배선이 여기 모여
 * 있어서 무엇이 무엇에 의존하는지가 한 화면에 보인다.
 *
 * <p>{@code @EnableScheduling} 을 여기서도 선언한다 — 정시율 게이지의 갱신이 {@code libs/messaging} 의
 * 릴레이·정리 스위치를 따라 조용히 꺼지지 않게(다른 네 서비스와 같다).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
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

    // --- KPI (§5.5 「KPI — 두 축, 뷰」, §9.1) ------------------------------------

    /**
     * @param jdbc JDBC 템플릿
     * @return {@code kpi_delivery_hourly}
     */
    @Bean
    public DeliveryKpis deliveryKpis(JdbcTemplate jdbc) {
        return new JdbcDeliveryKpis(jdbc);
    }

    /**
     * @param kpis        배송 축 KPI
     * @param routeCounts 라우트 진행 집계
     * @param meters      Micrometer 레지스트리
     * @param clock       창의 기준 시각 (불변규칙 12)
     * @return 정시율 · 결과 수 · 빠진 수 · 갱신 나이 · 라우트 진행 — 한 갱신
     */
    @Bean
    public KpiGauges kpiGauges(DeliveryKpis kpis, RouteCounts routeCounts, MeterRegistry meters,
            Clock clock) {
        return new KpiGauges(kpis, routeCounts, meters, clock);
    }

    /**
     * @param jdbc JDBC 템플릿
     * @return {@code dawnline_routes} 의 집계
     */
    @Bean
    public RouteCounts routeCounts(JdbcTemplate jdbc) {
        return new JdbcRouteCounts(jdbc);
    }

    // --- 운영자 커맨드 (§5.5 「커맨드 위임」, ADR-052) ------------------------------

    /**
     * @param jdbc 트랜잭션 밖에서 부른다 — {@code PENDING} 이 위임 전에 커밋된다
     * @param json {@code request} JSONB 직렬화
     * @return {@code audit_logs}
     */
    @Bean
    public AuditLog auditLog(JdbcTemplate jdbc, JsonMapper json) {
        return new JdbcAuditLog(jdbc, json);
    }

    /**
     * @param audit    {@code audit_logs}
     * @param core     코어 위임({@link CoreClientsConfig})
     * @param clock    저장 정밀도로 자른 시계
     * @param registry 카운터 레지스트리
     * @return 커맨드 유스케이스
     */
    @Bean
    public RunOpsCommandUseCase runOpsCommand(AuditLog audit, CoreCommands core, Clock clock, MeterRegistry registry) {
        return new OpsCommandService(audit, core, clock, registry);
    }

    /**
     * @param core 코어 조회({@link CoreClientsConfig})
     * @return 격리 목록 유스케이스 — 감사 없음(§5.5)
     */
    @Bean
    public ReadModelViews readModelViews(JdbcTemplate jdbc) {
        return new JdbcReadModelViews(jdbc);
    }

    @Bean
    public QueryReadModelUseCase queryReadModel(ReadModelViews views, DeliveryKpis kpis, CoreQueries core,
            Clock clock) {
        return new ReadModelQueryService(views, kpis, core, clock);
    }

    @Bean
    public ListQuarantinedOutboxUseCase listQuarantinedOutbox(CoreQueries core) {
        return new QuarantineQueryService(core);
    }
}
