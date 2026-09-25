package com.dawnline.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.observability.DawnlineMetrics;
import com.dawnline.ops.application.ReadModelRetentionCleaner;
import com.dawnline.ops.application.port.out.OrderColumn;
import com.dawnline.ops.application.port.out.OrderRows;
import com.dawnline.ops.application.port.out.Patch;
import com.dawnline.ops.application.port.out.ReadModelRetention;
import com.dawnline.ops.application.port.out.RouteColumn;
import com.dawnline.ops.application.port.out.RouteRows;
import com.dawnline.ops.application.port.out.WaveRows;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 읽기 모델 보존 — 실제 PostgreSQL 에서 (ADR-058, DESIGN.md §5.5 「updated_at」).
 *
 * <p>무엇을 지우는가(종결 술어 · NULL · 가드 · 상한)와 무엇을 세는가(걸린 행)는 SQL 의 일이라 여기서 본다. 정리기의
 * 순서·임계·실패의 모양은 {@code ReadModelRetentionCleanerTest} 가 본다.
 *
 * <h2>픽스처는 자기 행만 만든다</h2>
 * 나이를 주입된 시계에서 뽑아 90일·365일 <em>전</em>으로 둔 행을 만들고 테스트가 끝나면 지운다. 정리기와 셈은 표
 * 전체를 보지만 그만큼 오래된 행은 이 테스트의 것뿐이다 — 다른 IT 의 행은 전부 지금 만들어진다.
 */
@SpringBootTest(classes = OpsApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("읽기 모델 보존 — 종결 90일 · 상한 365일 · 가드 · 걸린 행 (ADR-058)")
class ReadModelRetentionIT extends OpsIntegrationTestBase {

    /** 주문 쪽 축의 값 전부와 「아직 모름」. */
    private static final List<@Nullable String> ORDER_STATUSES =
            Arrays.asList(null, "PLACED", "PLANNED", "UNSERVICEABLE", "DISPATCHED", "CANCELLED");

    /** 배송 결과의 값 전부와 「아직 없음」. */
    private static final List<@Nullable String> OUTCOMES = Arrays.asList(null, "FAILED", "COMPLETED");

    @Autowired
    private ReadModelRetentionCleaner cleaner;

    @Autowired
    private ReadModelRetention retention;

    @Autowired
    private OrderRows orderRows;

    @Autowired
    private RouteRows routeRows;

    @Autowired
    private WaveRows waveRows;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private Clock clock;

    private TransactionTemplate transactions;
    private Instant now;
    private final Set<UUID> createdOrders = new LinkedHashSet<>();
    private final Set<UUID> createdRoutes = new LinkedHashSet<>();
    private final Set<UUID> createdWaves = new LinkedHashSet<>();

    /**
     * 이 IT 가 자기 자리에서 끄고 미룬다 (CLAUDE.md). 정리는 테스트가 직접 부른다 — 스케줄이 같은 순간 돌면
     * 「무엇이 지웠나」를 가를 수 없다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void 공유_자원을_끈다(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("dawnline.ops.kpi.initial-delay-ms", () -> "3600000");
        registry.add("dawnline.ops.retention.cleanup-initial-delay-ms", () -> "3600000");
    }

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        now = clock.instant();
    }

    @AfterEach
    void 만든_행을_지운다() {
        createdOrders.forEach(id -> jdbc.update("DELETE FROM rm_orders WHERE order_id = ?", id));
        createdRoutes.forEach(id -> jdbc.update("DELETE FROM rm_routes WHERE route_id = ?", id));
        createdWaves.forEach(id -> jdbc.update("DELETE FROM rm_waves WHERE wave_id = ?", id));
        createdOrders.clear();
        createdRoutes.clear();
        createdWaves.clear();
    }

    // --- rm_orders ----------------------------------------------------------

    @Test
    void 지우는_술어와_세는_술어는_서로의_여집합이다() {
        // 축의 값 전부 × 결과의 값 전부(NULL 포함) — 18 행이 모두 90일을 넘겼다. 정리 뒤에 남은 행이 곧 센 행이어야
        // 한다: 지워지지도 세어지지도 않은 행은 조용히 쌓이고, 둘 다인 행은 셈이 거짓이다.
        Map<UUID, String> rows = new java.util.LinkedHashMap<>();
        for (String status : ORDER_STATUSES) {
            for (String outcome : OUTCOMES) {
                rows.put(order(status, outcome, null, null, daysAgo(91)), status + "/" + outcome);
            }
        }
        // 「둘 다인 행」은 지우기 전에 세어야 보인다. 정리기는 지운 뒤에 세므로, 셈이 종결 행까지 넓어져도 그 행은
        // 이미 없다 — 드러나는 날은 배치 상한에 걸려 종결 행이 남은 날이고 그날 게이지가 부푼다. 이 테스트의 옛
        // 형태는 셈에서 `delivery_outcome IS NULL` 을 지워도 초록이었다(2026-09-25, 음성 표본으로 확인 — fulfillment
        // 의 같은 셈을 만들다 알았다). 그래서 같은 스냅숏에서 센다.
        long countedBefore = Objects.requireNonNull(transactions.execute(
                status -> retention.countStuckOrdersUpdatedBefore(now.minus(Duration.ofDays(90)))));

        ReadModelRetentionCleaner.Result result = cleaner.deleteExpired();

        List<String> kept = new ArrayList<>();
        rows.forEach((id, label) -> {
            if (orderExists(id)) {
                kept.add(label);
            }
        });
        assertThat(kept).as("남는 것은 비종결 넷 — 결과가 없고 주문 쪽도 끝나지 않았다(NULL 은 종결이 아니다)")
                .containsExactlyInAnyOrder("null/null", "PLACED/null", "PLANNED/null", "DISPATCHED/null");
        assertThat(countedBefore).as("지우기 전에도 셈은 같은 넷이다 — 종결 행을 세지 않는다").isEqualTo(kept.size());
        assertThat(result.stuckOrders()).as("남은 행이 곧 센 행이다").isEqualTo(kept.size());
        assertThat(meters.get(DawnlineMetrics.RM_ORDERS_STUCK.meterName()).gauge().value()).isEqualTo(kept.size());
    }

    @Test
    void 보존_안쪽의_종결_행은_남는다() {
        UUID young = order("DISPATCHED", "COMPLETED", null, null, daysAgo(89));
        UUID cancelledDelivered = order("CANCELLED", "COMPLETED", null, null, daysAgo(91));

        cleaner.deleteExpired();

        assertThat(orderExists(young)).isTrue();
        assertThat(orderExists(cancelledDelivered))
                .as("「취소됐는데 배송된」 예외 목록의 상한은 보존 90일이다 (ADR-058 결정 2)")
                .isFalse();
    }

    @Test
    void 상한은_비종결_행도_지운다() {
        UUID ancient = order("PLANNED", null, null, null, daysAgo(366));
        UUID unknown = order(null, null, null, null, daysAgo(366));
        UUID stuck = order("DISPATCHED", null, null, null, daysAgo(364));

        cleaner.deleteExpired();

        assertThat(orderExists(ancient)).as("365일 상한 — 정리이지 정책이 아니다").isFalse();
        assertThat(orderExists(unknown)).isFalse();
        assertThat(orderExists(stuck)).isTrue();
    }

    // --- rm_routes · rm_waves -----------------------------------------------

    @Test
    void 라우트와_웨이브는_참조하는_주문이_없을_때만_90일에_지운다() {
        UUID guardedRoute = route(daysAgo(91));
        UUID guardedWave = wave(daysAgo(91));
        order("DISPATCHED", null, guardedRoute, guardedWave, daysAgo(91));
        UUID orphanRoute = route(daysAgo(91));
        UUID orphanWave = wave(daysAgo(91));
        UUID youngRoute = route(daysAgo(89));
        UUID youngWave = wave(daysAgo(89));

        cleaner.deleteExpired();

        assertThat(exists("rm_routes", "route_id", guardedRoute)).as("가드 — 걸린 주문의 라우트").isTrue();
        assertThat(exists("rm_waves", "wave_id", guardedWave)).as("가드 — 걸린 주문의 웨이브").isTrue();
        assertThat(exists("rm_routes", "route_id", orphanRoute)).isFalse();
        assertThat(exists("rm_waves", "wave_id", orphanWave)).isFalse();
        assertThat(exists("rm_routes", "route_id", youngRoute)).isTrue();
        assertThat(exists("rm_waves", "wave_id", youngWave)).isTrue();
    }

    @Test
    void 종결_주문이_지워진_같은_실행에서_그_라우트와_웨이브도_지운다() {
        // 순서의 확인 — 주문이 먼저 지워져야 가드를 넘는다. 순서가 뒤집히면 한 실행씩 밀린다.
        UUID route = route(daysAgo(91));
        UUID wave = wave(daysAgo(91));
        order("DISPATCHED", "COMPLETED", route, wave, daysAgo(91));

        cleaner.deleteExpired();

        assertThat(exists("rm_routes", "route_id", route)).isFalse();
        assertThat(exists("rm_waves", "wave_id", wave)).isFalse();
    }

    // --- 나이의 칸 ----------------------------------------------------------

    @Test
    void 키만으로_만든_행도_나이를_갖고_쓰기가_나이를_옮긴다() {
        // lock 은 행을 키만으로 만든다(ADR-051 결정 1) — 패치가 빈 채로 끝나도 그 행은 나이를 가져야 한다(V6).
        UUID orderId = track(createdOrders, Ids.newId());
        UUID routeId = track(createdRoutes, Ids.newId());
        UUID waveId = track(createdWaves, Ids.newId());
        Instant created = now.minus(Duration.ofMinutes(10));
        transactions.executeWithoutResult(status -> {
            orderRows.lock(List.of(orderId), created);
            routeRows.lock(List.of(routeId), created);
            waveRows.lock(waveId, created);
        });
        assertThat(updatedAt("rm_orders", "order_id", orderId)).isEqualTo(created);
        assertThat(updatedAt("rm_routes", "route_id", routeId)).isEqualTo(created);
        assertThat(updatedAt("rm_waves", "wave_id", waveId)).isEqualTo(created);

        transactions.executeWithoutResult(status -> {
            orderRows.lock(List.of(orderId), now);
            routeRows.lock(List.of(routeId), now);
            waveRows.lock(waveId, now);
        });
        assertThat(updatedAt("rm_routes", "route_id", routeId)).as("잠금은 쓰기가 아니다").isEqualTo(created);

        transactions.executeWithoutResult(status -> {
            orderRows.write(orderId, Patch.of(OrderColumn.class).set(OrderColumn.ORDER_STATUS, "PLACED"), now);
            routeRows.write(routeId, Patch.of(RouteColumn.class).set(RouteColumn.AT_RISK, Boolean.TRUE), now);
        });
        assertThat(updatedAt("rm_orders", "order_id", orderId)).isEqualTo(now);
        assertThat(updatedAt("rm_routes", "route_id", routeId)).isEqualTo(now);
    }

    // --- 게이지 -------------------------------------------------------------

    @Test
    void 정리가_있는_표마다_성공_나이_게이지가_기동_때부터_있다() {
        // 없는 시계열에는 알림이 울리지 않는다(§9.1 「짝」). outbox 는 이 IT 가 껐다.
        for (String table : List.of("rm_orders", "rm_routes", "rm_waves", "processed_events")) {
            assertThat(meters.find(DawnlineMetrics.RETENTION_LAST_SUCCESS_AGE.meterName()).tag(MessagingMetrics.TAG_TABLE, table)
                    .gauge())
                    .as("table=%s", table)
                    .isNotNull();
        }
        assertThat(meters.find(DawnlineMetrics.RM_ORDERS_STUCK.meterName()).gauge()).isNotNull();
    }

    // --- 픽스처 --------------------------------------------------------------

    private Instant daysAgo(int days) {
        return now.minus(Duration.ofDays(days));
    }

    private static UUID track(Set<UUID> created, UUID id) {
        created.add(id);
        return id;
    }

    private UUID order(@Nullable String status, @Nullable String outcome, @Nullable UUID routeId,
            @Nullable UUID waveId, Instant updatedAt) {
        UUID orderId = track(createdOrders, Ids.newId());
        jdbc.update("""
                INSERT INTO rm_orders (order_id, order_status, delivery_outcome, route_id, wave_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, orderId, status, outcome, routeId, waveId, utc(updatedAt));
        return orderId;
    }

    private UUID route(Instant updatedAt) {
        UUID routeId = track(createdRoutes, Ids.newId());
        jdbc.update("INSERT INTO rm_routes (route_id, updated_at) VALUES (?, ?)", routeId, utc(updatedAt));
        return routeId;
    }

    private UUID wave(Instant updatedAt) {
        UUID waveId = track(createdWaves, Ids.newId());
        jdbc.update("INSERT INTO rm_waves (wave_id, updated_at) VALUES (?, ?)", waveId, utc(updatedAt));
        return waveId;
    }

    private boolean orderExists(UUID orderId) {
        return exists("rm_orders", "order_id", orderId);
    }

    private boolean exists(String table, String key, UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM " + table + " WHERE " + key + " = ?)", Boolean.class, id));
    }

    private Instant updatedAt(String table, String key, UUID id) {
        return jdbc.queryForObject("SELECT updated_at FROM " + table + " WHERE " + key + " = ?",
                OffsetDateTime.class, id).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
