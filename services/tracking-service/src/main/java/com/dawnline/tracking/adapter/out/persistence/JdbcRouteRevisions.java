package com.dawnline.tracking.adapter.out.persistence;

import com.dawnline.tracking.application.port.out.RouteRevisions;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * {@code route_revisions} 어댑터
 * ([ADR-045](docs/adr/ADR-045-revision-comparison-is-per-route.md), DESIGN.md §5.4).
 *
 * <p>비교와 기록이 <strong>한 문장</strong>이다. {@code ON CONFLICT … DO UPDATE … WHERE} 는
 * 행 잠금 아래에서 술어를 보므로, 같은 라우트의 두 개정이 동시에 들어와도 높은 쪽만 남는다.
 * 읽고-비교하고-쓰면 그 사이가 창이 되어 둘 다 자기가 최신이라고 읽는다.
 *
 * <p>술어가 {@code <} 인 것은 계약이다 — 같은 번호의 재발행은 새 정보를 담지 않는다
 * ({@code route.assigned.v1} 의 {@code revision} 설명).
 *
 * <p>이 행은 tracking 쓰기 계층의 <strong>부모</strong>이기도 하다 — 라우트 행 → {@code shipments}
 * ([ADR-070](docs/adr/ADR-070-tracking-writes-lock-the-route-first.md) 결정 1). 라우트의 편차도 여기 산다(결정 2).
 */
public class JdbcRouteRevisions implements RouteRevisions {

    private static final String CLAIM_SQL = """
            INSERT INTO route_revisions (route_id, revision, camp_id, planned_departure, applied_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (route_id) DO UPDATE
               SET revision = EXCLUDED.revision,
                   camp_id = EXCLUDED.camp_id,
                   planned_departure = EXCLUDED.planned_departure,
                   applied_at = EXCLUDED.applied_at,
                   deviation_seconds = 0
             WHERE route_revisions.revision < EXCLUDED.revision
            """;

    /** 부모 행을 id 순으로 — 정렬은 DB 가 한다(자바의 {@code UUID} 비교는 부호가 있어 순서가 다르다). */
    private static final String LOCK_SQL =
            "SELECT route_id FROM route_revisions WHERE route_id = ANY(?) ORDER BY route_id FOR UPDATE";

    private static final String DEVIATION_SQL =
            "UPDATE route_revisions SET deviation_seconds = ? WHERE route_id = ? AND deviation_seconds <> ?";

    private static final String FIND_SQL =
            "SELECT camp_id, revision, planned_departure FROM route_revisions WHERE route_id = ?";

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 같은 트랜잭션에 참여하는 JDBC 템플릿
     */
    public JdbcRouteRevisions(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean claim(UUID routeId, int revision, UUID campId, Instant plannedDeparture,
            Instant appliedAt) {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(campId, "campId");
        Objects.requireNonNull(plannedDeparture, "plannedDeparture");
        Objects.requireNonNull(appliedAt, "appliedAt");
        if (revision < 1) {
            throw new IllegalArgumentException("revision 은 1 이상이어야 합니다: " + revision);
        }
        // TIMESTAMPTZ 에는 OffsetDateTime 으로 넘긴다 — 드라이버가 Instant 를 직접 받지 않는다.
        return jdbc.update(CLAIM_SQL, routeId, revision, campId,
                plannedDeparture.atOffset(ZoneOffset.UTC), appliedAt.atOffset(ZoneOffset.UTC)) > 0;
    }

    @Override
    public void lockForWrite(Collection<UUID> routeIds) {
        Objects.requireNonNull(routeIds, "routeIds");
        if (routeIds.isEmpty()) {
            return;
        }
        UUID[] ids = routeIds.stream().distinct().toArray(UUID[]::new);
        jdbc.query(LOCK_SQL, statement -> statement.setArray(1, statement.getConnection().createArrayOf("uuid", ids)),
                (RowCallbackHandler) row -> {
                    // 잡는 것이 목적이다 — 읽을 값이 없다.
                });
    }

    @Override
    public void recordDeviation(UUID routeId, Duration deviation) {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(deviation, "deviation");
        int seconds = Math.toIntExact(deviation.toSeconds());
        jdbc.update(DEVIATION_SQL, seconds, routeId, seconds);
    }

    @Override
    public Optional<RoutePlanned> find(UUID routeId) {
        Objects.requireNonNull(routeId, "routeId");
        return jdbc.query(FIND_SQL, rs -> rs.next()
                ? Optional.of(new RoutePlanned(rs.getObject(1, UUID.class), rs.getInt(2),
                        rs.getObject(3, java.time.OffsetDateTime.class).toInstant()))
                : Optional.empty(), routeId);
    }
}
