package com.dawnline.ops.adapter.out.persistence;

import com.dawnline.ops.application.port.out.RouteCounts;
import com.dawnline.ops.domain.RouteProgress;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code rm_routes} 의 진행 집계 (DESIGN.md §9.1 {@code dawnline_routes}).
 *
 * <p>「완료」는 칸이 아니라 판정이다: 출발했고, 그 라우트에 결과가 없는 주문(취소 제외)이 남지 않았다. 주문 수는
 * stop 수가 아니므로(한 stop 에 주문 여럿) {@code completed_count + failed_count} 를 {@code stop_count} 와 견주지 않고
 * 남은 주문을 찾는다. 계획이 오지 않은 행은 찾을 것이 없으므로 창 없이 전부 센다 — 모르는 것을 창으로 자르지 않는다.
 *
 * <h2>{@code EXISTS} 가 아니라 {@code LATERAL … LIMIT 1} 이다</h2>
 * 같은 판정을 {@code EXISTS} 로 쓰면 플래너가 그것을 <strong>해시 서브플랜</strong>으로 바꿔 {@code rm_orders} 전체를
 * 순차 스캔한다 — 창이 라우트를 하루치(피크 1,258)로 묶어도 그 탐색의 수를 묶지 못한다. 보존 90일 · 1,361만 행에서
 * 매분 221 ms(캐시가 찬 뒤) · 1.5 s(처음), 버퍼 19만 5천 읽기였다. {@code LATERAL … LIMIT 1} 은 라우트마다
 * {@code ix_rmo_route} 로 찾고 첫 행에서 멈춘다 — 같은 데이터에서 91 ms(처음 154 ms), 두 형태의 결과는 같다
 * ({@code docs/benchmarks/phase7-route-progress-count.md}). 인덱스는 더하지 않는다.
 *
 * <p>값의 이름은 {@link RouteProgress} 의 이름 그대로 리터럴로 적는다.
 */
public class JdbcRouteCounts implements RouteCounts {

    static final String COUNT_SQL = """
            SELECT r.camp_id,
                   CASE WHEN r.revision IS NULL OR r.status IS NULL THEN 'UNKNOWN'
                        WHEN r.status = 'ASSIGNED' THEN 'ASSIGNED'
                        WHEN pending.route_id IS NOT NULL THEN 'IN_PROGRESS'
                        ELSE 'COMPLETED'
                   END AS progress,
                   count(*) AS routes
              FROM rm_routes r
              LEFT JOIN LATERAL (SELECT o.route_id
                                   FROM rm_orders o
                                  WHERE o.route_id = r.route_id
                                    AND r.status = 'DEPARTED'
                                    AND o.delivery_outcome IS NULL
                                    AND o.order_status IS DISTINCT FROM 'CANCELLED'
                                  LIMIT 1) pending ON true
             WHERE r.revision IS NULL OR r.planned_departure >= ?
             GROUP BY 1, 2
            """;

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc JDBC 템플릿
     */
    public JdbcRouteCounts(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<CampRoutes> count(Instant since) {
        return jdbc.query(COUNT_SQL, (rs, n) -> new CampRoutes(
                        rs.getObject("camp_id", UUID.class),
                        RouteProgress.valueOf(rs.getString("progress")),
                        rs.getLong("routes")),
                // TIMESTAMPTZ 에는 OffsetDateTime 으로 넘긴다 — 드라이버가 Instant 를 직접 받지 않는다.
                Objects.requireNonNull(since, "since").atOffset(ZoneOffset.UTC));
    }
}
