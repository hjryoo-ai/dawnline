package com.dawnline.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Ids;
import com.dawnline.tracking.application.ShipmentEventPartitions;
import com.dawnline.tracking.application.port.out.EventPartitions;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code shipment_events} 일 파티션 — 생성·삭제·경계 (DESIGN.md §5.4, {@code V1__tracking.sql}).
 *
 * <h2>테스트끼리 순서에 기대지 않는다</h2>
 * 파티션은 부모 하나를 공유하는 전역 상태다. 그래서 생성 검사는 <strong>미래 날짜</strong>를
 * 쓴다 — 보존 삭제는 언제나 「오늘보다 훨씬 앞」만 지우므로 미래 파티션은 다른 테스트가 지울 수
 * 없다. 삭제 검사만 과거 날짜를 쓰고, 그 파티션은 같은 메서드 안에서 만들고 지운다.
 * 실행 순서는 보장되지 않으며, 그것에 기댄 통과는 통과가 아니다(§13 여덟째 축).
 */
@SpringBootTest
@DisplayName("shipment_events 일 파티션")
class ShipmentEventPartitionIT extends TrackingIntegrationTestBase {

    /** {@code ShipmentEventPartitions} 의 기본 선행일. 설정 기본값과 같은 값이다. */
    private static final int AHEAD_DAYS = 7;

    private static final int RETENTION_DAYS = 30;

    @Autowired
    private EventPartitions partitions;

    @Autowired
    private ShipmentEventPartitions scheduled;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 이 IT 가 자기 자리에서 끄고 미룬다 (CLAUDE.md — 기반은 이 속성에 의견을 갖지 않는다).
     *
     * <ul>
     *   <li>outbox 릴레이: 이 IT 는 아무것도 발행하지 않는다. 켜 두면 브로커 없는 컨텍스트에서
     *       advisory lock 만 쥐고 있게 된다 — {@code GeoFallbackIT} 가 그랬다(Phase 5-0).</li>
     *   <li>파티션 스케줄러: 생성 시점을 테스트가 정한다. 스케줄러가 먼저 돌면 "만들기 전" 상태를
     *       볼 수 없고, 그 실패는 실행 순서에 따라 나타났다 사라진다.</li>
     * </ul>
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void 공유_자원을_끈다(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("dawnline.tracking.partitions.initial-delay-ms", () -> "3600000");
    }

    @BeforeEach
    void 전제_파티션_스케줄러는_아직_돌지_않았다() {
        assertThat(scheduled)
                .as("스케줄러 빈이 없으면 이 IT 가 검사하는 경로가 통째로 없다")
                .isNotNull();
    }

    @Test
    void 마이그레이션이_어제부터_오늘_더하기_선행일까지_만들어_둔다() {
        LocalDate today = jdbc.queryForObject("SELECT CURRENT_DATE", LocalDate.class);

        assertThat(today).isNotNull();
        assertThat(partitionExists(today.minusDays(1)))
                .as("자정 직후에 도착하는 어제 날짜의 스캔이 갈 곳이 있어야 한다")
                .isTrue();
        assertThat(partitionExists(today)).isTrue();
        assertThat(partitionExists(today.plusDays(AHEAD_DAYS)))
                .as("기동 직후에도 %d일치가 덮여 있어야 스케줄러가 한 번도 못 돌아도 버틴다", AHEAD_DAYS)
                .isTrue();
    }

    @Test
    void DEFAULT_파티션을_두지_않는다() {
        Integer defaults = jdbc.queryForObject("""
                SELECT count(*)
                  FROM pg_inherits i
                  JOIN pg_class c ON c.oid = i.inhrelid
                 WHERE i.inhparent = 'shipment_events'::regclass
                   AND pg_get_expr(c.relpartbound, c.oid) = 'DEFAULT'
                """, Integer.class);

        assertThat(defaults)
                .as("DEFAULT 가 있으면 범위 밖 행이 조용히 쌓이고, 그 날짜의 파티션 생성이 며칠 뒤에 실패한다")
                .isZero();
    }

    @Test
    void 회전은_어제부터_오늘_더하기_선행일까지_만든다() {
        LocalDate base = LocalDate.of(2035, 3, 15);

        ShipmentEventPartitions.Rotated rotated = rotateAt(base).rotate();

        assertThat(rotated.created())
                .as("어제(1) + 오늘(1) + 선행 %d일", AHEAD_DAYS)
                .isEqualTo(1 + 1 + AHEAD_DAYS);
        assertThat(partitionExists(base.minusDays(1))).isTrue();
        assertThat(partitionExists(base.plusDays(AHEAD_DAYS))).isTrue();
        assertThat(partitionExists(base.plusDays(AHEAD_DAYS + 1L)))
                .as("선행일 너머는 만들지 않는다")
                .isFalse();
    }

    @Test
    void 이미_있는_날은_다시_만들지_않는다() {
        LocalDate base = LocalDate.of(2035, 4, 15);
        ShipmentEventPartitions manager = rotateAt(base);

        assertThat(manager.rotate().created()).isEqualTo(1 + 1 + AHEAD_DAYS);
        assertThat(manager.rotate().created())
                .as("두 인스턴스가 같은 창을 동시에 만들어도 서로를 깨지 않는다")
                .isZero();
    }

    @Test
    void 보존_경계일은_남고_그_앞만_지워진다() {
        // 과거 날짜를 쓰는 유일한 테스트다. 같은 메서드 안에서 만들고 지운다.
        LocalDate first = LocalDate.of(2019, 5, 1);
        partitions.ensure(first, 10);
        assertThat(partitionExists(first)).isTrue();

        LocalDate boundary = first.plusDays(4);
        int dropped = partitions.dropBefore(boundary);

        assertThat(dropped).isGreaterThanOrEqualTo(4);
        assertThat(partitionExists(boundary.minusDays(1)))
                .as("경계보다 앞선 날은 지워진다")
                .isFalse();
        assertThat(partitionExists(boundary))
                .as("경계일 자신은 남는다 — 보존 %d일은 오늘−%d 를 포함한다", RETENTION_DAYS, RETENTION_DAYS)
                .isTrue();
    }

    @Test
    void 파티션이_없는_날의_INSERT_는_그_자리에서_실패한다() {
        // DEFAULT 파티션을 두지 않은 결과다. 조용히 쌓이는 대신 원인이 곧 메시지가 된다.
        LocalDate uncovered = LocalDate.of(2034, 1, 1);
        assertThat(partitionExists(uncovered)).isFalse();

        assertThatThrownBy(() -> insertScan(uncovered.atTime(9, 0).toInstant(ZoneOffset.UTC)))
                .hasMessageContaining("no partition of relation");
    }

    @Test
    void 파티션_경계는_만든_세션의_타임존과_무관하게_UTC_자정이다() {
        // 전제를 실제로 깨는 검사다: 타임존을 바꿔 놓고 **같은 커넥션에서** 만든다.
        // 다른 커넥션이면 SET TIME ZONE 이 생성에 닿지 않아 이 테스트는 아무것도 보지 않는다.
        String bound = jdbc.execute((ConnectionCallback<String>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TIME ZONE 'Asia/Seoul'");
                statement.execute("SELECT tracking_ensure_event_partitions(DATE '2035-05-10', 2)");
                // 읽기도 타임존을 타므로(pg_get_expr 은 세션 존으로 렌더한다) UTC 로 되돌린다.
                statement.execute("SET TIME ZONE 'UTC'");
                try (ResultSet rows = statement.executeQuery("""
                        SELECT pg_get_expr(c.relpartbound, c.oid)
                          FROM pg_class c
                         WHERE c.relname = 'shipment_events_20350510'
                        """)) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        });

        assertThat(bound)
                .as("세션 존으로 만들었다면 KST 자정(= 2035-05-09 15:00+00)이 경계가 된다 — "
                        + "같은 스크립트가 환경마다 다른 날을 가른다")
                .contains("'2035-05-10 00:00:00+00'")
                .contains("'2035-05-11 00:00:00+00'");
    }

    @Test
    void 사건은_UTC_날짜의_파티션에_들어간다() {
        LocalDate day = LocalDate.of(2035, 6, 10);
        partitions.ensure(day, 2);

        UUID late = insertScan(day.atTime(23, 30).toInstant(ZoneOffset.UTC));
        UUID next = insertScan(day.plusDays(1).atTime(0, 30).toInstant(ZoneOffset.UTC));

        assertThat(partitionOf(late)).isEqualTo(partitionName(day));
        assertThat(partitionOf(next)).isEqualTo(partitionName(day.plusDays(1)));
    }

    @Test
    void 게이지가_앞으로_남은_날을_센다() {
        scheduled.rotate();
        double gauge = meters.get("dawnline_shipment_partitions_ahead").gauge().value();

        assertThat(gauge)
                .as("회전 직후에는 적어도 「오늘 + 선행 %d일」 이 덮여 있다 (§9.1)", AHEAD_DAYS)
                .isGreaterThanOrEqualTo(AHEAD_DAYS + 1.0);
        assertThat(gauge)
                .as("게이지는 상수가 아니라 관리자의 값을 읽는다")
                .isEqualTo(scheduled.partitionsAhead());
        // 등값을 요구하지 않는 이유: 이 게이지는 전역 최대 파티션 날짜를 보고, 이 클래스의
        // 다른 검사들이 미래 날짜를 만든다. 등값을 요구하면 이 통과가 실행 순서에 달린다 —
        // 그런 통과는 통과가 아니다(§13 여덟째 축). 「남은 날」 의 정확한 수는
        // ShipmentEventPartitionsTest 가 고정 시계로 본다.
    }

    private ShipmentEventPartitions rotateAt(LocalDate day) {
        Clock clock = Clock.fixed(day.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        return new ShipmentEventPartitions(partitions, clock, AHEAD_DAYS, RETENTION_DAYS);
    }

    private boolean partitionExists(LocalDate day) {
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relname = ?", Integer.class, partitionName(day));
        return found != null && found > 0;
    }

    private static String partitionName(LocalDate day) {
        return "shipment_events_%04d%02d%02d".formatted(day.getYear(), day.getMonthValue(), day.getDayOfMonth());
    }

    private UUID insertScan(Instant occurredAt) {
        UUID id = Ids.newId();
        jdbc.update("""
                INSERT INTO shipment_events (id, order_id, route_id, type, occurred_at)
                VALUES (?, ?, ?, 'ARRIVED', ?)
                """, id, Ids.newId(), Ids.newId(), OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        return id;
    }

    private String partitionOf(UUID eventId) {
        List<String> names = jdbc.queryForList(
                "SELECT tableoid::regclass::text FROM shipment_events WHERE id = ?", String.class, eventId);
        assertThat(names).as("방금 넣은 행 하나").hasSize(1);
        return names.getFirst();
    }
}
