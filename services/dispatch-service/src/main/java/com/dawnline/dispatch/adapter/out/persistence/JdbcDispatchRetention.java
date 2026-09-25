package com.dawnline.dispatch.adapter.out.persistence;

import com.dawnline.dispatch.application.port.out.DispatchRetention;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * dispatch 보존의 고르기 · 삭제 · 셈 (ADR-059).
 *
 * <p>상태 값은 <strong>리터럴</strong>이다(CLAUDE.md 부분 인덱스 규칙과 같은 습관 — 질의가 한 모양이어야 인덱스를
 * 판단할 수 있다). 질의 문자열이 공개 상수인 이유는 운영 크기 EXPLAIN 과 계획을 지키는 IT({@code DispatchRetentionIT})가
 * <em>이 문자열 그대로</em>를 재기 때문이다.
 *
 * <h2>종결은 한 조각이다</h2>
 * {@link #SETTLED} 를 고르기 셋과 셈 하나가 함께 쓴다 — 셈이 고르기의 여집합이라는 것이 문자열 수준에서 참이 되게
 * 한다. stop 쪽은 <strong>종결 목록</strong>({@code NOT IN}) 으로 적는다: §4.7 이 허용하는 새 상태 값이 조용히 종결로
 * 읽히지 않는다(결정 2 — 모름은 종결이 아니다). {@code route_stops.status} 는 {@code NOT NULL} 이라 {@code NOT IN} 이
 * NULL 에 빠지지 않는다.
 *
 * <h2>계열 삭제는 자식부터, 인덱스의 앞머리로</h2>
 * {@code ix_routes_plan} → {@code route_stops (route_id, seq)} UNIQUE → {@code route_stop_orders} PK. 부모를 지울 때의
 * FK 검사도 같은 앞머리를 탄다. 같은 트랜잭션에서 자식을 먼저 지우므로 가드({@code NOT EXISTS})가 필요 없다(결정 6).
 */
public class JdbcDispatchRetention implements DispatchRetention {

    /**
     * 계획 {@code p} 가 종결이다 — 발행됐거나 실패했고, 끝나지 않은 stop 이 하나도 없다.
     */
    public static final String SETTLED = """
            p.status IN ('PUBLISHED', 'FAILED')
                   AND NOT EXISTS (SELECT 1 FROM routes r JOIN route_stops s ON s.route_id = r.id
                                    WHERE r.plan_id = p.id
                                      AND s.status NOT IN ('CANCELLED', 'COMPLETED', 'FAILED'))""";

    /** 설명 단계의 고르기. 설명이 이미 없는 계획은 다시 고르지 않는다 — {@code EXISTS} 는 {@code ix_expl_plan_order} 의 앞머리다. */
    public static final String SELECT_WITH_EXPLANATIONS_SQL = """
            SELECT p.id, p.wave_id FROM route_plans p
             WHERE p.finished_at < ?
               AND\s""" + SETTLED + """

               AND EXISTS (SELECT 1 FROM plan_explanations e WHERE e.plan_id = p.id)
             ORDER BY p.finished_at
             LIMIT ?
            """;

    /** 후보 단계의 고르기. {@code EXISTS} 는 {@code ix_cand_wave} 의 앞머리다. */
    public static final String SELECT_WITH_CANDIDATES_SQL = """
            SELECT p.id, p.wave_id FROM route_plans p
             WHERE p.finished_at < ?
               AND\s""" + SETTLED + """

               AND EXISTS (SELECT 1 FROM dispatch_candidates c WHERE c.wave_id = p.wave_id)
             ORDER BY p.finished_at
             LIMIT ?
            """;

    /** 설명 — {@code ix_expl_plan_order} 의 앞머리. */
    public static final String DELETE_EXPLANATIONS_SQL = "DELETE FROM plan_explanations WHERE plan_id = ?";

    /** 그 웨이브의 후보 전부 — {@code ix_cand_wave (wave_id, status)} 의 앞머리. 상태는 보지 않는다(결정 3). */
    public static final String DELETE_CANDIDATES_SQL = "DELETE FROM dispatch_candidates WHERE wave_id = ?";

    /** 90일 단계의 고르기. */
    public static final String SELECT_SETTLED_SQL = """
            SELECT p.id, p.wave_id FROM route_plans p
             WHERE p.finished_at < ?
               AND\s""" + SETTLED + """

             ORDER BY p.finished_at
             LIMIT ?
            """;

    /** 상한의 고르기 — 종결과 무관하다. 첫 실행 중인 계획은 {@code finished_at} 이 없어 {@code started_at} 으로 잰다. */
    public static final String SELECT_AGED_SQL = """
            SELECT p.id, p.wave_id FROM route_plans p
             WHERE COALESCE(p.finished_at, p.started_at) < ?
             ORDER BY COALESCE(p.finished_at, p.started_at)
             LIMIT ?
            """;

    public static final String DELETE_STOP_ORDERS_SQL = """
            DELETE FROM route_stop_orders o
             USING route_stops s, routes r
             WHERE o.stop_id = s.id AND s.route_id = r.id AND r.plan_id = ?
            """;

    public static final String DELETE_STOPS_SQL = """
            DELETE FROM route_stops s
             USING routes r
             WHERE s.route_id = r.id AND r.plan_id = ?
            """;

    public static final String DELETE_ROUTES_SQL = "DELETE FROM routes WHERE plan_id = ?";

    public static final String DELETE_PLAN_SQL = "DELETE FROM route_plans WHERE id = ?";

    /**
     * 계획 없는 웨이브의 후보 — 상한. {@code updated_at} 에 인덱스가 없어 순차 스캔이다: 평상시 대상이 0 이라 한
     * 실행에 한 번 돌고 끝난다([측정](docs/benchmarks/phase7-dispatch-retention.md) §3). 가드의 반대쪽은
     * {@code route_plans.wave_id} UNIQUE 다.
     */
    public static final String DELETE_ORPHAN_CANDIDATES_SQL = """
            DELETE FROM dispatch_candidates
             WHERE ctid IN (
                   SELECT c.ctid FROM dispatch_candidates c
                    WHERE c.updated_at < ?
                      AND NOT EXISTS (SELECT 1 FROM route_plans p WHERE p.wave_id = c.wave_id)
                    ORDER BY c.updated_at
                    LIMIT ?)
            """;

    /** 설명 · 후보 단계 고르기의 여집합 — 종결 계획은 {@code finished_at} 을 가지므로 두 나이가 같다. */
    public static final String COUNT_STUCK_SQL = """
            SELECT count(*) FROM route_plans p
             WHERE COALESCE(p.finished_at, p.started_at) < ?
               AND NOT (""" + SETTLED + """
            )
            """;

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 이 서비스의 데이터소스 — 부르는 쪽의 트랜잭션에 참여한다
     */
    public JdbcDispatchRetention(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<PlanRef> settledPlansWithExplanationsFinishedBefore(Instant finishedBefore, int limit) {
        return select(SELECT_WITH_EXPLANATIONS_SQL, finishedBefore, limit);
    }

    @Override
    public int deleteExplanations(PlanRef plan) {
        return jdbc.update(DELETE_EXPLANATIONS_SQL, Objects.requireNonNull(plan, "plan").planId());
    }

    @Override
    public List<PlanRef> settledPlansWithCandidatesFinishedBefore(Instant finishedBefore, int limit) {
        return select(SELECT_WITH_CANDIDATES_SQL, finishedBefore, limit);
    }

    @Override
    public int deleteCandidates(PlanRef plan) {
        return jdbc.update(DELETE_CANDIDATES_SQL, Objects.requireNonNull(plan, "plan").waveId());
    }

    @Override
    public List<PlanRef> settledPlansFinishedBefore(Instant finishedBefore, int limit) {
        return select(SELECT_SETTLED_SQL, finishedBefore, limit);
    }

    @Override
    public List<PlanRef> plansAgedBefore(Instant agedBefore, int limit) {
        return select(SELECT_AGED_SQL, agedBefore, limit);
    }

    @Override
    public PlanRows deletePlan(PlanRef plan) {
        Objects.requireNonNull(plan, "plan");
        // 순서가 FK 사슬이다 — 바꾸면 부모 쪽 문장이 FK 위반으로 터진다(조용히 틀리지는 않는다).
        int stopOrders = jdbc.update(DELETE_STOP_ORDERS_SQL, plan.planId());
        int stops = jdbc.update(DELETE_STOPS_SQL, plan.planId());
        int routes = jdbc.update(DELETE_ROUTES_SQL, plan.planId());
        int explanations = jdbc.update(DELETE_EXPLANATIONS_SQL, plan.planId());
        int candidates = jdbc.update(DELETE_CANDIDATES_SQL, plan.waveId());
        int plans = jdbc.update(DELETE_PLAN_SQL, plan.planId());
        return new PlanRows(stopOrders, stops, routes, explanations, candidates, plans);
    }

    @Override
    public int deleteOrphanCandidatesUpdatedBefore(Instant updatedBefore, int limit) {
        Objects.requireNonNull(updatedBefore, "updatedBefore");
        requireLimit(limit);
        // TIMESTAMPTZ 에는 OffsetDateTime 으로 넘긴다 — 드라이버가 Instant 를 직접 받지 않는다.
        return jdbc.update(DELETE_ORPHAN_CANDIDATES_SQL, updatedBefore.atOffset(ZoneOffset.UTC), limit);
    }

    @Override
    public long countStuckPlansAgedBefore(Instant agedBefore) {
        Long count = jdbc.queryForObject(COUNT_STUCK_SQL, Long.class,
                Objects.requireNonNull(agedBefore, "agedBefore").atOffset(ZoneOffset.UTC));
        return count == null ? 0 : count;
    }

    private List<PlanRef> select(String sql, Instant threshold, int limit) {
        Objects.requireNonNull(threshold, "threshold");
        requireLimit(limit);
        return jdbc.query(sql, (row, index) -> new PlanRef(row.getObject(1, UUID.class), row.getObject(2, UUID.class)),
                threshold.atOffset(ZoneOffset.UTC), limit);
    }

    private static void requireLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit 은 1 이상이어야 합니다: " + limit);
        }
    }
}
