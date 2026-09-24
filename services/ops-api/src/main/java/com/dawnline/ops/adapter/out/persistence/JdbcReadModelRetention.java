package com.dawnline.ops.adapter.out.persistence;

import com.dawnline.ops.application.port.out.ReadModelRetention;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 읽기 모델 보존의 삭제와 셈 — {@code ctid} 를 경유한 {@code LIMIT} 배치 (ADR-058 결정 5, ADR-023 과 같은 모양).
 *
 * <p>상태 값은 <strong>리터럴</strong>이다(CLAUDE.md 부분 인덱스 규칙과 같은 습관 — 질의가 한 모양이어야 인덱스를
 * 판단할 수 있다). 질의 문자열이 공개 상수인 이유는 운영 크기 EXPLAIN 과 계획을 지키는 IT({@code ReadModelRetentionIndexIT})가
 * <em>이 문자열 그대로</em>를 재기 때문이다.
 *
 * <h2>종결 술어는 NULL 에서 보수적으로 떨어진다</h2>
 * {@code order_status IN (…) OR delivery_outcome IS NOT NULL} — {@code order_status} 가 NULL 이면 앞쪽이 NULL 이고,
 * 결과도 없으면 전체가 NULL 이라 {@code WHERE} 를 통과하지 못한다. 걸린 행의 셈은 그 반대라 NULL 을 명시적으로
 * 비종결로 센다. 두 술어가 서로의 여집합이라는 것은 {@code ReadModelRetentionIT} 가 본다.
 */
public class JdbcReadModelRetention implements ReadModelRetention {

    public static final String DELETE_SETTLED_ORDERS_SQL = """
            DELETE FROM rm_orders
             WHERE ctid IN (
                   SELECT o.ctid FROM rm_orders o
                    WHERE o.updated_at < ?
                      AND (o.order_status IN ('CANCELLED', 'UNSERVICEABLE') OR o.delivery_outcome IS NOT NULL)
                    ORDER BY o.updated_at
                    LIMIT ?)
            """;

    public static final String DELETE_ORDERS_CAP_SQL = """
            DELETE FROM rm_orders
             WHERE ctid IN (
                   SELECT o.ctid FROM rm_orders o
                    WHERE o.updated_at < ?
                    ORDER BY o.updated_at
                    LIMIT ?)
            """;

    /** 가드의 반대쪽은 {@code ix_rmo_route} 다(§5.5 — 재집계가 쓰는 인덱스). */
    public static final String DELETE_ROUTES_SQL = """
            DELETE FROM rm_routes
             WHERE ctid IN (
                   SELECT r.ctid FROM rm_routes r
                    WHERE r.updated_at < ?
                      AND NOT EXISTS (SELECT 1 FROM rm_orders o WHERE o.route_id = r.route_id)
                    ORDER BY r.updated_at
                    LIMIT ?)
            """;

    /** 가드의 반대쪽은 {@code ix_rmo_wave} 다. */
    public static final String DELETE_WAVES_SQL = """
            DELETE FROM rm_waves
             WHERE ctid IN (
                   SELECT w.ctid FROM rm_waves w
                    WHERE w.updated_at < ?
                      AND NOT EXISTS (SELECT 1 FROM rm_orders o WHERE o.wave_id = w.wave_id)
                    ORDER BY w.updated_at
                    LIMIT ?)
            """;

    /** 종결 술어의 여집합 — NULL 을 비종결로 센다. */
    public static final String COUNT_STUCK_SQL = """
            SELECT count(*) FROM rm_orders o
             WHERE o.updated_at < ?
               AND (o.order_status IS NULL OR o.order_status NOT IN ('CANCELLED', 'UNSERVICEABLE'))
               AND o.delivery_outcome IS NULL
            """;

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 이 서비스의 데이터소스 — 부르는 쪽의 트랜잭션에 참여한다
     */
    public JdbcReadModelRetention(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public int deleteSettledOrdersUpdatedBefore(Instant updatedBefore, int limit) {
        return delete(DELETE_SETTLED_ORDERS_SQL, updatedBefore, limit);
    }

    @Override
    public int deleteOrdersUpdatedBefore(Instant updatedBefore, int limit) {
        return delete(DELETE_ORDERS_CAP_SQL, updatedBefore, limit);
    }

    @Override
    public int deleteUnreferencedRoutesUpdatedBefore(Instant updatedBefore, int limit) {
        return delete(DELETE_ROUTES_SQL, updatedBefore, limit);
    }

    @Override
    public int deleteUnreferencedWavesUpdatedBefore(Instant updatedBefore, int limit) {
        return delete(DELETE_WAVES_SQL, updatedBefore, limit);
    }

    @Override
    public long countStuckOrdersUpdatedBefore(Instant updatedBefore) {
        Long count = jdbc.queryForObject(COUNT_STUCK_SQL, Long.class,
                Objects.requireNonNull(updatedBefore, "updatedBefore").atOffset(ZoneOffset.UTC));
        return count == null ? 0 : count;
    }

    private int delete(String sql, Instant threshold, int limit) {
        Objects.requireNonNull(threshold, "threshold");
        if (limit < 1) {
            throw new IllegalArgumentException("limit 은 1 이상이어야 합니다: " + limit);
        }
        // TIMESTAMPTZ 에는 OffsetDateTime 으로 넘긴다 — 드라이버가 Instant 를 직접 받지 않는다.
        return jdbc.update(sql, threshold.atOffset(ZoneOffset.UTC), limit);
    }
}
