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
 * {@code rm_routes} 의 진행 집계 (DESIGN.md §9.1 {@code dawnline_routes}, ADR-061).
 *
 * <p>「완료」는 칸이 아니라 판정이고, 그 판정은 <strong>쓰기 때</strong> 이미 했다 — 재집계가 {@code completed_at} 을
 * 적는다({@code JdbcRouteRows.RECOUNT_SQL}). 그래서 이 질의는 {@code rm_routes} 하나만 읽는다.
 *
 * <h2>끝나지 않은 일에는 창이 없다</h2>
 * 앞의 술어(끝나지 않은 것 — 출발 전 · 진행 중 · 계획을 모르는 행)는 창과 무관하게 들고, 뒤의 술어(창)는 나머지(완료 ·
 * void)만 거른다. 출발한 지 30시간 된 라우트가 아직 끝나지 않았다면 운영자가 가장 먼저 볼 라우트다. 출발 전도 같다 —
 * 계획 출발을 한참 넘기고도 떠나지 않은 라우트는 끝나지 않은 일이다. 재계획이 비운 라우트는 {@code assigned} 가 아니라
 * void 라서 거기 쌓이지 않는다. 계획을 모르는 행은 {@code completed_at} · {@code live_count} 가 언제나 {@code NULL} 이라
 * (다시 세지 않는다) 앞의 술어에 든다.
 *
 * <h2>인덱스가 없다 — 그리고 {@code rm_orders} 를 읽지 않는다</h2>
 * 보존 90일 11만 행에서 순차 스캔 한 번이 5–8 ms 다. 끝나지 않은 라우트의 부분 인덱스는 창 쪽이 순차 스캔이라 값을 하지
 * 않았다({@code docs/benchmarks/phase7-route-progress-count.md} 「둘째 판」). 7-1 의 첫 판은 라우트마다 {@code rm_orders}
 * 를 찾았다 — 그 모양으로 돌아가지 않는다는 것을 {@code KpiViewsIT} 가 계획으로 본다.
 *
 * <p>값의 이름은 {@link RouteProgress} 의 이름 그대로 리터럴로 적는다.
 */
public class JdbcRouteCounts implements RouteCounts {

    public static final String COUNT_SQL = """
            SELECT camp_id,
                   CASE WHEN revision IS NULL OR status IS NULL THEN 'UNKNOWN'
                        WHEN live_count = 0 THEN 'VOID'
                        WHEN status = 'ASSIGNED' THEN 'ASSIGNED'
                        WHEN completed_at IS NULL THEN 'IN_PROGRESS'
                        ELSE 'COMPLETED'
                   END AS progress,
                   count(*) AS routes
              FROM rm_routes
             WHERE (completed_at IS NULL AND live_count IS DISTINCT FROM 0)
                OR planned_departure >= ?
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
