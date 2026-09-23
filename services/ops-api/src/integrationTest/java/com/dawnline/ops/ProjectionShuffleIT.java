package com.dawnline.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.ops.adapter.in.messaging.ListenerTopics;
import com.dawnline.ops.adapter.in.messaging.ProjectionListener;
import com.dawnline.ops.adapter.in.messaging.ProjectionScenario;
import com.dawnline.ops.adapter.in.messaging.ProjectionScenario.Event;
import com.dawnline.ops.application.port.out.OrderColumn;
import com.dawnline.ops.application.port.out.RouteColumn;
import com.dawnline.ops.application.port.out.WaveColumn;
import com.dawnline.ops.support.RowDiff;
import com.dawnline.ops.support.Shuffles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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

/**
 * 프로젝션은 순서와 무관하다 — ADR-051 의 관측 근거 (결정 6).
 *
 * <p>같은 사실 집합({@link ProjectionScenario})을 인과 순서로 넣은 결과가 기준 행이고, 같은 집합을
 * 씨앗 고정 셔플로 다시 넣은 결과가 그것과 같은지 본다. 실제 PostgreSQL 이다 — 잠금·{@code COALESCE}·
 * 집계 질의·생성 칸이 전부 여기서 돈다. 레코드는 Kafka 가 부르는 것과 같은 리스너 메서드로 넣는다
 * ({@link ListenerTopics#deliver}) — 브로커를 거치면 순서를 <em>우리가</em> 정할 수 없고, 그러면
 * 깨졌을 때 씨앗 하나로 다시 만들 수 없다.
 *
 * <h2>무엇을 빼는가 — 전부 이유와 함께</h2>
 * 토픽·표·칸 모두 카탈로그에서 시작하고 뺀다(§13 규칙 2). 토픽은
 * {@link ProjectionScenario#EXCLUDED_TOPICS}(비어 있다), 칸은 {@link #EXCLUDED_COLUMNS} 하나다.
 */
@SpringBootTest(classes = OpsApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ProjectionShuffleIT — 순서를 뒤섞어도 읽기 모델이 같다 (ADR-051)")
class ProjectionShuffleIT extends OpsIntegrationTestBase {

    /** 비교에서 빼는 칸과 그 이유. */
    static final Map<String, String> EXCLUDED_COLUMNS = Map.of(
            "updated_at", "사실이 아니라 프로젝션의 기록 — 마지막으로 행을 만진 시각이라 정의상 처리 순서를 탄다(§5.5)");

    /** 핸들러가 쓰지 않고 다시 세는 칸 (ADR-051 결정 4). */
    static final Map<String, Set<String>> AGGREGATES = Map.of(
            "rm_orders", Set.of(),
            "rm_waves", Set.of("order_count"),
            "rm_routes", Set.of("completed_count", "failed_count"));

    /** 칸 대조에서 빼는 표와 그 이유. */
    static final Map<String, String> EXCLUDED_TABLES = Map.of(
            "rm_kpi_hourly", "채우는 것은 묶음 B 의 KPI 단계다 — 그 커밋에서 이 제외를 지운다. 순서 비교에는 들어간다(지금은 빈 표)");

    private static final int ROUNDS = 25;

    /**
     * 릴레이를 끄고 리스너 컨테이너를 기동하지 않는다 — 이 클래스는 레코드를 직접 넣고, 브로커가 없다.
     * 공유 자원을 쓰는 IT 는 자기 자리에서 켜고 끈다(CLAUDE.md).
     */
    @DynamicPropertySource
    static void noBroker(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    private ProjectionListener listener;

    @Autowired
    private JdbcTemplate jdbc;

    private final ProjectionScenario scenario = new ProjectionScenario(EventContracts.load());
    private ReadModelTables tables;

    @BeforeEach
    void setUp() {
        tables = new ReadModelTables(jdbc);
        wipe();
    }

    @AfterEach
    void wipe() {
        tables.delete(scenario.keys());
        List<UUID> eventIds = new ArrayList<>();
        for (Event event : scenario.causalOrder()) {
            eventIds.add(event.eventId());
        }
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("DELETE FROM processed_events WHERE event_id = ANY (?)");
            statement.setArray(1, connection.createArrayOf("uuid", eventIds.toArray()));
            return statement;
        });
    }

    @Test
    void 시나리오는_구독하는_토픽을_전부_쓴다_뺀_것만_빼고() {
        Set<String> expected = new TreeSet<>(ListenerTopics.of().keySet());
        expected.removeAll(ProjectionScenario.EXCLUDED_TOPICS.keySet());

        Set<String> used = scenario.causalOrder().stream().map(Event::topic)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(used).isEqualTo(expected);
    }

    @Test
    void 핸들러가_쓰는_칸이_표의_칸과_같다_키와_생성_칸과_집계와_기록만_빼고() {
        // 칸 enum 과 DDL 은 서로를 비추는 목록이다(CLAUDE.md). 칸이 늘었는데 enum 에 없으면 그 칸은
        // 아무도 쓰지 않는 칸이고, enum 에만 있으면 어댑터의 UPDATE 가 기동 뒤 첫 사실에서 터진다.
        Map<String, Class<? extends Enum<?>>> writers = Map.of(
                "rm_orders", OrderColumn.class, "rm_waves", WaveColumn.class, "rm_routes", RouteColumn.class);
        Set<String> checked = new TreeSet<>(tables.tables());
        checked.removeAll(EXCLUDED_TABLES.keySet());
        assertThat(writers.keySet()).as("대조하는 표 = rm_* 전부 − 뺀 표").isEqualTo(checked);

        for (String table : checked) {
            Set<String> expected = new TreeSet<>(tables.columns(table));
            expected.removeAll(tables.primaryKey(table));
            expected.removeAll(tables.generatedColumns(table));
            expected.removeAll(AGGREGATES.get(table));
            expected.removeAll(EXCLUDED_COLUMNS.keySet());

            Set<String> written = Arrays.stream(writers.get(table).getEnumConstants())
                    .map(c -> c.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toCollection(TreeSet::new));
            assertThat(written).as("%s 를 쓰는 칸", table).isEqualTo(expected);
        }
        EXCLUDED_TABLES.forEach((table, reason) -> {
            assertThat(tables.tables()).as("뺀 표 %s 는 있는 표여야 한다", table).contains(table);
            assertThat(reason).isNotBlank();
        });
    }

    @Test
    void 인과_순서의_결과가_시나리오가_말하는_행이다() {
        replay(scenario.causalOrder());

        // O4 — 취소됐는데 배송됐다. 운영자의 예외 목록 질의 그대로(§5.5 「DDL 정정」).
        assertThat(jdbc.queryForList("""
                SELECT order_id FROM rm_orders
                 WHERE order_status = 'CANCELLED' AND delivery_outcome = 'COMPLETED' AND order_id = ANY (?)
                """, UUID.class, (Object) scenario.keys().toArray(UUID[]::new)))
                .containsExactly(scenario.o4);
        // 생성 칸 — 쓰는 사람이 없는데 값이 있다. O2 는 실패라 약속과 무관하게 false.
        assertThat(onTime(scenario.o1)).containsExactly(true, true);
        assertThat(onTime(scenario.o2)).containsExactly(false, false);
        assertThat(jdbc.queryForObject("SELECT route_id FROM rm_orders WHERE order_id = ?", UUID.class, scenario.o2))
                .as("재계획이 옮긴 주문의 라우트").isEqualTo(scenario.r2);
        assertThat(jdbc.queryForMap("SELECT completed_count, failed_count, revision, status FROM rm_routes WHERE route_id = ?",
                scenario.r2))
                .containsEntry("completed_count", 2).containsEntry("failed_count", 1)
                .containsEntry("revision", 2).containsEntry("status", "DEPARTED");
        assertThat(jdbc.queryForMap("SELECT status, order_count, route_count FROM rm_waves WHERE wave_id = ?",
                scenario.waveId))
                .containsEntry("status", "PLANNED").containsEntry("order_count", 4).containsEntry("route_count", 2);
        assertThat(jdbc.queryForMap("SELECT camp_id, wave_id, order_status FROM rm_orders WHERE order_id = ?",
                scenario.o5))
                .as("배차 불가 — 캠프·웨이브는 끝까지 비어 있다")
                .containsEntry("camp_id", null).containsEntry("wave_id", null)
                .containsEntry("order_status", "UNSERVICEABLE");
    }

    @Test
    void 씨_고정_셔플의_최종_행이_인과_순서의_최종_행과_같다() {
        List<Event> causal = scenario.causalOrder();
        replay(causal);
        var baseline = tables.snapshot(scenario.keys(), EXCLUDED_COLUMNS.keySet());
        assertThat(baseline.get("rm_orders")).as("기준 행이 있다 — 비어 있으면 아래 비교는 공허하다").hasSize(5);

        for (long seed = 1; seed <= ROUNDS; seed++) {
            wipe();
            List<Event> shuffled = Shuffles.of(causal, seed);
            // 전제: 이 회차가 실제로 순서를 바꿨다. 항등 순열은 아무것도 검사하지 않는다(결정 6 의 4).
            assertThat(shuffled).as("씨 %d 의 셔플이 인과 순서와 달라야 한다", seed).isNotEqualTo(causal);

            replay(shuffled);

            assertThat(RowDiff.between(baseline, tables.snapshot(scenario.keys(), EXCLUDED_COLUMNS.keySet()),
                    scenario::name))
                    .as("씨 %d — 순서: %s", seed, shuffled)
                    .isEmpty();
        }
    }

    private void replay(List<Event> order) {
        long offset = 0;
        for (Event event : order) {
            ListenerTopics.deliver(listener, new ConsumerRecord<>(event.topic(), 0, offset++, event.key(), event.value()));
        }
    }

    private List<Boolean> onTime(UUID orderId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT on_time_promised, on_time_revised FROM rm_orders WHERE order_id = ?", orderId);
        List<Boolean> values = new ArrayList<>();
        values.add((Boolean) row.get("on_time_promised"));
        values.add((Boolean) row.get("on_time_revised"));
        return values;
    }
}
