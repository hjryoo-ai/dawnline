package com.dawnline.tracking.adapter.out.persistence;

import com.dawnline.tracking.application.port.out.TrackingRetention;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 보존 정리의 삭제 — {@code ctid} 를 경유한 {@code LIMIT} 배치 (ADR-058 결정 5, ADR-023 과 같은 모양).
 *
 * <p>{@code DELETE} 에는 {@code LIMIT} 이 없어서 부질의로 대상의 {@code ctid} 를 집는다. 부질의가 오래된
 * 순으로 정렬하므로 반복할 때마다 앞으로 나아간다. 상태 값은 <strong>리터럴</strong>이다 — 바인드로 넣을 이유가
 * 없고, 인덱스 판단을 할 때 질의가 한 모양이어야 한다(CLAUDE.md 부분 인덱스 규칙과 같은 습관).
 *
 * <p>질의 문자열이 공개 상수인 이유: 운영 크기 EXPLAIN({@code docs/benchmarks/phase7-retention-indexes.md})과
 * 계획을 지키는 IT({@code TrackingRetentionIndexIT})가 <em>이 문자열 그대로</em>를 잰다. 측정한 질의와 도는 질의가
 * 갈라지면 측정은 아무것도 말하지 않는다.
 */
public class JdbcTrackingRetention implements TrackingRetention {

    /** 종결 배송 — §5.4 상태 머신의 끝 셋. */
    public static final String DELETE_SETTLED_SHIPMENTS_SQL = """
            DELETE FROM shipments
             WHERE ctid IN (
                   SELECT s.ctid FROM shipments s
                    WHERE s.status IN ('COMPLETED', 'FAILED', 'CANCELLED')
                      AND s.updated_at < ?
                    ORDER BY s.updated_at
                    LIMIT ?)
            """;

    /** 상한 — 상태를 보지 않는다. */
    public static final String DELETE_SHIPMENTS_CAP_SQL = """
            DELETE FROM shipments
             WHERE ctid IN (
                   SELECT s.ctid FROM shipments s
                    WHERE s.updated_at < ?
                    ORDER BY s.updated_at
                    LIMIT ?)
            """;

    /**
     * 참조하는 배송이 없는 개정. 가드의 반대쪽은 {@code ix_ship_route (route_id, stop_seq)} 의 선두 칸이다.
     * 기간이 가드를 대신하지 않는다 — 비종결 배송은 365일까지 남고, 그동안 그 라우트의 개정도 남아야
     * 뒤늦은 {@code route.assigned} 가 번호 비교에 막힌다.
     */
    public static final String DELETE_REVISIONS_SQL = """
            DELETE FROM route_revisions
             WHERE ctid IN (
                   SELECT r.ctid FROM route_revisions r
                    WHERE r.applied_at < ?
                      AND NOT EXISTS (SELECT 1 FROM shipments s WHERE s.route_id = r.route_id)
                    ORDER BY r.applied_at
                    LIMIT ?)
            """;

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 이 서비스의 데이터소스 — 부르는 쪽의 트랜잭션에 참여한다
     */
    public JdbcTrackingRetention(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public int deleteSettledShipmentsUpdatedBefore(Instant updatedBefore, int limit) {
        return delete(DELETE_SETTLED_SHIPMENTS_SQL, updatedBefore, limit);
    }

    @Override
    public int deleteShipmentsUpdatedBefore(Instant updatedBefore, int limit) {
        return delete(DELETE_SHIPMENTS_CAP_SQL, updatedBefore, limit);
    }

    @Override
    public int deleteUnreferencedRevisionsAppliedBefore(Instant appliedBefore, int limit) {
        return delete(DELETE_REVISIONS_SQL, appliedBefore, limit);
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
