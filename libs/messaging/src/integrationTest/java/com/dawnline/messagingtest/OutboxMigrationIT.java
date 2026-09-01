package com.dawnline.messagingtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * 공통 마이그레이션의 <strong>업그레이드 경로</strong> (DESIGN.md §5.1).
 *
 * <p>다른 통합 테스트는 빈 데이터베이스에 전체 마이그레이션을 한 번에 적용한다. 그건 새 환경에서만
 * 성립하는 경로다. 이미 돌고 있는 5개 서비스 DB 에는 {@code V000_1}·{@code V000_2} 가 적용된
 * <em>데이터가 든</em> 테이블이 있고, 배포는 거기에 다음 스크립트를 얹는다.
 *
 * <p>그 경로에서만 나는 실패가 있다 — 채워진 테이블에 {@code NOT NULL} 컬럼을 붙이거나,
 * 쓰이고 있는 인덱스를 바꾸는 경우다. 그래서 여기서는 일부러 <strong>중간 버전까지만</strong>
 * 마이그레이션하고, 행을 넣은 뒤, 나머지를 얹는다.
 *
 * <p>스프링 컨텍스트를 쓰지 않는다. 검증 대상이 SQL 과 Flyway 이지 애플리케이션이 아니다.
 */
class OutboxMigrationIT extends MessagingIntegrationTestBase {

    private static final String DATABASE = "dawnline_migration";
    private static final String COMMON_MIGRATIONS = "classpath:db/migration/common";

    @Test
    void 데이터가_있는_기존_스키마에_격리_마이그레이션을_얹을_수_있다() throws Exception {
        String url = createDatabaseWithoutMigrations(DATABASE);

        // (1) 격리 이전 상태까지만 적용한다 — 운영 중인 DB 가 지금 이 상태다.
        Flyway.configure()
                .dataSource(url, username(), password())
                .locations(COMMON_MIGRATIONS)
                .target("000.2")
                .load()
                .migrate();
        assertThat(columnExists(url, "outbox_events", "failed_at")).isFalse();

        // (2) 데이터를 넣는다. 빈 테이블에서는 드러나지 않는 실패가 여기서 드러난다.
        UUID publishedId = insertRow(url, true);
        UUID unpublishedId = insertRow(url, false);

        // (3) 나머지를 얹는다.
        Flyway.configure()
                .dataSource(url, username(), password())
                .locations(COMMON_MIGRATIONS)
                .load()
                .migrate();

        // 컬럼이 생기고 기존 행에 기본값이 채워진다.
        assertThat(columnExists(url, "outbox_events", "failed_at")).isTrue();
        assertThat(columnExists(url, "outbox_events", "publish_attempts")).isTrue();
        assertThat(query(url, "SELECT publish_attempts FROM outbox_events WHERE id = '" + unpublishedId + "'"))
                .containsExactly("0");
        assertThat(query(url, "SELECT failed_at FROM outbox_events WHERE id = '" + unpublishedId + "'"))
                .containsExactly((String) null);
        // 기존 행은 그대로 남는다 — 마이그레이션이 데이터를 건드리지 않는다.
        assertThat(query(url, "SELECT count(*) FROM outbox_events")).containsExactly("2");
        assertThat(query(url,
                "SELECT count(*) FROM outbox_events WHERE id = '" + publishedId + "' AND published_at IS NOT NULL"))
                .containsExactly("1");

        // 부분 인덱스가 새 조건으로 다시 만들어졌다. 릴레이의 조회 조건과 정확히 같아야 인덱스를 탄다.
        assertThat(query(url, """
                SELECT pg_get_expr(i.indpred, i.indrelid)
                  FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                 WHERE c.relname = 'ix_outbox_unpublished'
                """).getFirst())
                .contains("published_at IS NULL")
                .contains("failed_at IS NULL");
    }

    @Test
    void 마이그레이션은_반복_적용해도_안전하다() throws Exception {
        String url = createDatabaseWithoutMigrations("dawnline_migration_repeat");
        var flyway = Flyway.configure().dataSource(url, username(), password()).locations(COMMON_MIGRATIONS).load();

        flyway.migrate();
        var second = flyway.migrate();

        // 두 번째 실행은 아무것도 적용하지 않는다. 체크섬이 어긋나면 여기서 예외가 난다.
        assertThat(second.migrationsExecuted).isZero();
        assertThat(query(url, "SELECT count(*) FROM flyway_schema_history WHERE success = false")).containsExactly("0");
    }

    private UUID insertRow(String url, boolean published) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO outbox_events
                      (id, aggregate_type, aggregate_id, event_type, topic, partition_key,
                       headers, payload, created_at, published_at)
                    VALUES ('%s', 'Order', '%s', 'order.placed', 'dawnline.order.placed.v1', '%s',
                            '{"eventType":"order.placed","schemaVersion":"1"}'::jsonb,
                            '{"orderId":"x"}'::jsonb, now(), %s)
                    """.formatted(id, UUID.randomUUID(), id, published ? "now()" : "NULL"));
        }
        return id;
    }

    private boolean columnExists(String url, String table, String column) throws Exception {
        return !query(url, """
                SELECT column_name FROM information_schema.columns
                 WHERE table_name = '%s' AND column_name = '%s'
                """.formatted(table, column)).isEmpty();
    }

    private List<String> query(String url, String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection connection = connect(url);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    private Connection connect(String url) throws Exception {
        return DriverManager.getConnection(url, username(), password());
    }
}
