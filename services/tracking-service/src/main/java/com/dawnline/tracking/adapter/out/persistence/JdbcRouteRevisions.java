package com.dawnline.tracking.adapter.out.persistence;

import com.dawnline.tracking.application.port.out.RouteRevisions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

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
 */
public class JdbcRouteRevisions implements RouteRevisions {

    private static final String CLAIM_SQL = """
            INSERT INTO route_revisions (route_id, revision, camp_id, planned_departure, applied_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (route_id) DO UPDATE
               SET revision = EXCLUDED.revision,
                   camp_id = EXCLUDED.camp_id,
                   planned_departure = EXCLUDED.planned_departure,
                   applied_at = EXCLUDED.applied_at
             WHERE route_revisions.revision < EXCLUDED.revision
            """;

    private static final String FIND_SQL =
            "SELECT camp_id, planned_departure FROM route_revisions WHERE route_id = ?";

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
    public Optional<RoutePlanned> find(UUID routeId) {
        Objects.requireNonNull(routeId, "routeId");
        return jdbc.query(FIND_SQL, rs -> rs.next()
                ? Optional.of(new RoutePlanned(rs.getObject(1, UUID.class),
                        rs.getObject(2, java.time.OffsetDateTime.class).toInstant()))
                : Optional.empty(), routeId);
    }
}
