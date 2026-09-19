package com.dawnline.tracking.adapter.out.persistence;

import com.dawnline.tracking.application.port.out.ShipmentEvents;
import com.dawnline.tracking.domain.ShipmentEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code shipment_events} 적재 (DESIGN.md §5.4).
 *
 * <p>JPA 를 쓰지 않는다. 이 테이블은 추가만 있고 상태 머신도 낙관적 락도 없는 <strong>로그</strong>
 * 라, 영속성 컨텍스트에 올려 둘 이유가 없다 — 올려 두면 뒤따르는 질의마다 더티 체크를 지난다
 * ([ADR-029](docs/adr/ADR-029-optimizer-io-is-bulk-not-orm.md) 가 dispatch 에서 잰 것과 같은 이유).
 *
 * <p>좌표는 {@code NUMERIC(9,6)} 이라 {@link BigDecimal} 로 넘긴다(불변규칙 9).
 *
 * <p><strong>{@code payload} 의 JSON 은 PostgreSQL 이 만든다</strong>({@code jsonb_build_object}).
 * 자바에서 문자열로 조립하면 따옴표·역슬래시·제어문자 이스케이프를 우리가 책임지게 되고, 그 값은
 * 기사가 자유 텍스트로 쓴 실패 사유다 — 손으로 만든 이스케이프가 틀리면 그때 깨지는 것은 행
 * 하나가 아니라 그 배치 전체다. DB 에 맡기면 그 책임이 없고, 부수적으로 드라이버 타입
 * ({@code PGobject})을 참조하지 않아 {@code postgresql} 이 {@code runtimeOnly} 에 머문다.
 *
 * <p>범위 밖 {@code occurred_at} 은 여기서 그대로 실패한다(DEFAULT 파티션 없음, §5.4). 자바에서
 * 덮인 범위를 다시 계산해 미리 막지 않는다 — 그러면 파티션 규칙이 두 곳이 되고 둘은 갈라진다.
 */
public class JdbcShipmentEvents implements ShipmentEvents {

    private static final int COORDINATE_SCALE = 6;

    /**
     * {@code payload} 가 {@code NULL} 인 것과 {@code {"failureReason": null}} 인 것은 다르다 —
     * 후자는 「사유 칸이 있는데 비어 있다」로 읽힌다. 사유가 없으면 칸도 없다.
     */
    private static final String INSERT_SQL = """
            INSERT INTO shipment_events (id, order_id, route_id, type, occurred_at, lat, lng, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?,
                    CASE WHEN CAST(? AS TEXT) IS NULL THEN NULL
                         ELSE jsonb_build_object('failureReason', CAST(? AS TEXT)) END)
            """;

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 같은 트랜잭션에 참여하는 JDBC 템플릿
     */
    public JdbcShipmentEvents(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void appendAll(Collection<ShipmentEvent> events) {
        Objects.requireNonNull(events, "events");
        if (events.isEmpty()) {
            return;
        }
        List<ShipmentEvent> rows = List.copyOf(events);
        jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {

            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                ShipmentEvent event = rows.get(index);
                statement.setObject(1, event.id());
                statement.setObject(2, event.orderId());
                statement.setObject(3, event.routeId());
                statement.setString(4, event.type().name());
                statement.setObject(5, event.occurredAt().atOffset(ZoneOffset.UTC));
                setCoordinate(statement, 6, event.lat());
                setCoordinate(statement, 7, event.lng());
                // 같은 값을 두 번 묶는다 — CASE 가 NULL 판정과 객체 조립 양쪽에서 쓴다.
                statement.setObject(8, event.failureReason(), Types.VARCHAR);
                statement.setObject(9, event.failureReason(), Types.VARCHAR);
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    private static void setCoordinate(PreparedStatement statement, int index, @Nullable Double value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NUMERIC);
            return;
        }
        statement.setBigDecimal(index,
                BigDecimal.valueOf(value).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP));
    }
}
