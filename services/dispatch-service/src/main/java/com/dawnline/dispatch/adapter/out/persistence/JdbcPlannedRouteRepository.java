package com.dawnline.dispatch.adapter.out.persistence;

import com.dawnline.common.Ids;
import com.dawnline.dispatch.application.port.out.PlannedRouteRepository;
import com.dawnline.dispatch.domain.optimizer.Explanation;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.PlannedStop;
import com.dawnline.dispatch.domain.optimizer.VehicleId;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 라우트·stop·설명 저장 (DESIGN.md §5.3, §7.1, [ADR-029]).
 *
 * <p>JPA 엔티티가 아니라 SQL 이다. 이 세 표는 <strong>쓰기만 하고 애그리거트로 읽지
 * 않는다</strong> — 읽는 쪽은 ops REST(5c)의 조회이고 그건 DTO 로 바로 뜬다. 엔티티를 만들면
 * 영속성 컨텍스트가 5,000 stop 을 들고 있게 되는데, 그것을 얻는 대가로 아무것도 얻지 못한다.
 *
 * <h2>{@code EntityManager} 가 아니라 {@code JdbcTemplate} 인 이유 (2026-09-08 정정)</h2>
 * 위 문단은 처음부터 옳았지만 <strong>절반만 실행돼 있었다.</strong> 엔티티를 만들지 않았을 뿐
 * {@code entityManager.createNativeQuery(...)} 를 행마다 돌렸고, Hibernate 는 네이티브 질의
 * 실행 전마다 영속성 컨텍스트를 auto-flush 한다 — 어느 표를 건드리는지 알 수 없으니 전부 본다.
 * 이 표들이 엔티티를 안 만든 것과 무관하게, <em>다른 곳이</em> 올려 둔 후보 5,000개를 문장마다
 * 훑었다. 계측: flush 10,652회 × 엔티티 5,001개, 라우트 저장 10.4초 · 설명 저장 9.8초.
 *
 * <p>{@code JdbcTemplate} 은 같은 트랜잭션에 참여하면서({@code DataSourceUtils}) 영속성
 * 컨텍스트를 지나지 않는다. 대가는 <strong>Hibernate 의 미반영 변경을 보지 못한다</strong>는
 * 것이고, 이 경로에서는 {@code route_plans} 행이 {@code insertIfAbsent}(네이티브 INSERT)로 이미
 * DB 에 있어 FK 가 성립한다. 그 가정이 깨지면 FK 위반으로 즉시 터진다 — 조용하지 않다.
 *
 * <p>배치는 {@code reWriteBatchedInserts=true} 와 짝이다(application.yml). 드라이버가 다중
 * VALUES 로 다시 쓰지 않으면 배치는 왕복만 줄이고 문장 수는 그대로다.
 */
public class JdbcPlannedRouteRepository implements PlannedRouteRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String INSERT_ROUTE = """
            INSERT INTO routes (id, plan_id, vehicle_id, seq_no, status, revision,
                                stop_count, distance_m, duration_s, cost_krw, version)
            VALUES (?, ?, ?, ?, 'PLANNED', 1, ?, ?, ?, ?, 0)
            """;

    private static final String INSERT_STOP = """
            INSERT INTO route_stops (id, route_id, seq, lat, lng, planned_arrival,
                                     planned_departure, service_s, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PLANNED')
            """;

    private static final String INSERT_STOP_ORDER =
            "INSERT INTO route_stop_orders (stop_id, order_id) VALUES (?, ?)";

    private static final String INSERT_EXPLANATION = """
            INSERT INTO plan_explanations (id, plan_id, order_id, vehicle_id, rule_name,
                                           outcome, detail)
            VALUES (?, ?, ?, ?, ?, ?, cast(? as jsonb))
            """;

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 같은 트랜잭션에 참여하는 JDBC 템플릿
     */
    public JdbcPlannedRouteRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<UUID> saveRoutes(UUID planId, List<PlannedRoute> routes) {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(routes, "routes");
        List<UUID> routeIds = new ArrayList<>(routes.size());
        for (int i = 0; i < routes.size(); i++) {
            routeIds.add(Ids.newId());
        }

        jdbc.batchUpdate(INSERT_ROUTE, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int i) throws SQLException {
                PlannedRoute route = routes.get(i);
                statement.setObject(1, routeIds.get(i));
                statement.setObject(2, planId);
                statement.setObject(3, route.vehicle().value());
                statement.setShort(4, (short) (i + 1));
                statement.setInt(5, route.stops().size());
                statement.setInt(6, route.distanceM());
                statement.setInt(7, route.durationS());
                statement.setLong(8, route.cost().krw());
            }

            @Override
            public int getBatchSize() {
                return routes.size();
            }
        });

        saveStops(routeIds, routes);
        return List.copyOf(routeIds);
    }

    /** stop 과 stop-주문 연결. 순번은 라우트 안에서만 의미가 있다(V3 지연 UNIQUE). */
    private void saveStops(List<UUID> routeIds, List<PlannedRoute> routes) {
        List<Object[]> stopRows = new ArrayList<>();
        List<Object[]> stopOrderRows = new ArrayList<>();
        for (int i = 0; i < routes.size(); i++) {
            UUID routeId = routeIds.get(i);
            for (PlannedStop planned : routes.get(i).stops()) {
                UUID stopId = Ids.newId();
                stopRows.add(new Object[] {stopId, routeId, (short) planned.seq(),
                        planned.stop().point().lat(), planned.stop().point().lng(),
                        planned.arrival().atOffset(ZoneOffset.UTC),
                        planned.departure().atOffset(ZoneOffset.UTC),
                        planned.stop().serviceSeconds()});
                for (OrderId orderId : planned.stop().orderIds()) {
                    stopOrderRows.add(new Object[] {stopId, orderId.value()});
                }
            }
        }
        jdbc.batchUpdate(INSERT_STOP, stopRows);
        jdbc.batchUpdate(INSERT_STOP_ORDER, stopOrderRows);
    }

    @Override
    public void saveExplanations(UUID planId, List<Explanation> explanations,
            Map<VehicleId, UUID> routeIds) {

        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(explanations, "explanations");
        List<Object[]> rows = new ArrayList<>(explanations.size());
        for (Explanation explanation : explanations) {
            rows.add(new Object[] {Ids.newId(), planId, explanation.orderId().value(),
                    explanation.vehicle() == null ? null : explanation.vehicle().value(),
                    explanation.ruleName(), explanation.outcome().name(),
                    JSON.writeValueAsString(explanation.detail())});
        }
        jdbc.batchUpdate(INSERT_EXPLANATION, rows);
    }
}
