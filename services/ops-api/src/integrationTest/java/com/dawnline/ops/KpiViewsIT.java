package com.dawnline.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Ids;
import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.ops.adapter.in.messaging.ListenerTopics;
import com.dawnline.ops.adapter.in.messaging.ProjectionListener;
import com.dawnline.ops.adapter.in.messaging.ProjectionScenario;
import com.dawnline.ops.adapter.in.messaging.ProjectionScenario.Event;
import com.dawnline.ops.application.OnTimeRatioGauges;
import com.dawnline.ops.application.OnTimeRatioGauges.Basis;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * KPI 두 축 — 뷰가 무엇을 세는가 (DESIGN.md §5.5 「KPI — 두 축, 뷰」, §9.1).
 *
 * <p>행은 이 클래스가 만든 캠프·표시로 만들고 그것만 지운다(픽스처는 만들고 지운다). 게이지가 창을
 * 벽시계로 자르므로 배송 축의 시각은 <strong>주입된 시계에서</strong> 뽑는다(CLAUDE.md) — 접수 축만
 * 보는 픽스처는 시계와 비교되지 않아 리터럴이다.
 */
@SpringBootTest(classes = OpsApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("KpiViewsIT — 배송 축·접수 축 뷰와 정시율 게이지")
class KpiViewsIT extends OpsIntegrationTestBase {

    /** 이 클래스가 만든 행의 표시 — 캠프가 없는 행(배차 불가)은 이것으로 지운다. */
    private static final UUID MARKER = UUID.fromString("0b5e0000-0000-7000-8000-00000000c0e1");

    /** 접수 축만 보는 버킷 — 다른 IT 의 행이 없는 시각. 시계와 비교되지 않는다. */
    private static final Instant INTAKE_HOUR = Instant.parse("2031-03-03T03:00:00Z");

    @DynamicPropertySource
    static void noBroker(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        // 기동 직후 스케줄 갱신이 이 검사의 refreshNow 와 섞이지 않게 — 검사가 직접 부른다.
        registry.add("dawnline.ops.kpi.on-time-initial-delay-ms", () -> "3600000");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private OnTimeRatioGauges gauges;

    @Autowired
    private ProjectionListener listener;

    private final UUID camp = Ids.newId();
    private final ProjectionScenario scenario = new ProjectionScenario(EventContracts.load());

    @AfterEach
    void wipe() {
        jdbc.update("DELETE FROM rm_orders WHERE camp_id = ? OR customer_id = ?", camp, MARKER);
        new ReadModelTables(jdbc).delete(scenario.keys());
        List<UUID> eventIds = new ArrayList<>();
        scenario.causalOrder().forEach(event -> eventIds.add(event.eventId()));
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("DELETE FROM processed_events WHERE event_id = ANY (?)");
            statement.setArray(1, connection.createArrayOf("uuid", eventIds.toArray()));
            return statement;
        });
    }

    @Test
    void 배송_축의_한_행은_한_모집단을_센다() {
        Instant hour = clock.instant().truncatedTo(ChronoUnit.HOURS);
        Instant end = hour.plus(Duration.ofMinutes(50));
        Instant later = hour.plus(Duration.ofHours(3));

        order("COMPLETED", "DISPATCHED", end, end, hour.plusSeconds(60), null);   // 정시 · 정시
        order("COMPLETED", "DISPATCHED", hour, later, hour.plusSeconds(120), null); // 원 약속 늦음 · 개정 정시 · 개정됨
        order("FAILED", "DISPATCHED", end, end, null, hour.plusSeconds(180));     // 분모에만
        order("COMPLETED", "CANCELLED", end, end, hour.plusSeconds(240), null);   // 취소 — 빠진다
        order("COMPLETED", "DISPATCHED", null, end, hour.plusSeconds(300), null); // 원 약속 모름 — 빠진다

        assertThat(jdbc.queryForMap("""
                SELECT delivered, failed, on_time_promised, on_time_revised, revised
                  FROM kpi_delivery_hourly WHERE camp_id = ? AND bucket_hour = ?
                """, camp, utc(hour)))
                .containsEntry("delivered", 2L).containsEntry("failed", 1L)
                .containsEntry("on_time_promised", 1L).containsEntry("on_time_revised", 2L)
                .as("revised — 두 정시율의 격차(1 → 2)가 몇 건의 개정에서 왔는가").containsEntry("revised", 1L);
    }

    @Test
    void 게이지는_뷰의_24_버킷_합이고_실패가_분모에_있다() {
        Instant hour = clock.instant().truncatedTo(ChronoUnit.HOURS);
        Instant end = hour.plus(Duration.ofMinutes(50));
        order("COMPLETED", "DISPATCHED", end, end, hour.plusSeconds(60), null);
        order("COMPLETED", "DISPATCHED", hour, hour.plus(Duration.ofHours(3)), hour.plusSeconds(120), null);
        order("FAILED", "DISPATCHED", end, end, null, hour.minus(Duration.ofHours(23)).plusSeconds(1));
        // 창 밖 — 24 버킷 앞. 세면 분모가 4 가 된다.
        order("FAILED", "DISPATCHED", end, end, null, hour.minus(Duration.ofHours(24)).plusSeconds(1));

        gauges.refreshNow();

        assertThat(gauges.ratio(camp, Basis.PROMISED)).isEqualTo(1.0 / 3);
        assertThat(gauges.ratio(camp, Basis.REVISED)).isEqualTo(2.0 / 3);
        assertThat(gauges.ratio(Ids.newId(), Basis.PROMISED)).as("결과가 없는 캠프 — 0 이 아니다").isNaN();
    }

    @Test
    void 접수_축의_배차_불가는_캠프가_없는_행에만_있다() {
        placed(camp, "DISPATCHED");
        placed(camp, "PLANNED");
        placed(null, "UNSERVICEABLE");
        placed(null, "PLACED"); // fulfillment.planned 가 아직 — 캠프를 모른다

        assertThat(jdbc.queryForMap("SELECT orders, unserviceable FROM kpi_intake_hourly WHERE camp_id = ? AND bucket_hour = ?",
                camp, utc(INTAKE_HOUR)))
                .containsEntry("orders", 2L).containsEntry("unserviceable", 0L);
        assertThat(jdbc.queryForMap("SELECT orders, unserviceable FROM kpi_intake_hourly WHERE camp_id IS NULL AND bucket_hour = ?",
                utc(INTAKE_HOUR)))
                .containsEntry("orders", 2L).containsEntry("unserviceable", 1L);
    }

    @Test
    void 완료와_실패의_시각을_둘_다_가진_행은_적재되지_않는다() {
        Instant now = clock.instant();

        assertThatThrownBy(() -> order("COMPLETED", "DISPATCHED", now, now, now, now))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_rmo_outcome_time_exclusive");
    }

    @Test
    void 실패_뒤에_온_완료는_조용히_적히지_않고_크게_깨진다() {
        long offset = 0;
        for (Event event : scenario.causalOrder()) {
            ListenerTopics.deliver(listener, new ConsumerRecord<>(event.topic(), 0, offset++, event.key(), event.value()));
        }
        Event contradiction = scenario.o2CompletedAfterFailure();
        long last = offset;

        // 축은 FAILED → COMPLETED 를 앞으로 가는 것으로 판정한다 — 그래서 막는 것은 제약이다. 재배송이
        // 이 모양으로 들어오면 여기서 멈추고, 그때 「새 shipment 인가, 같은 행의 둘째 결과인가」를 먼저
        // 정한다(§5.5 재검토 조건).
        assertThatThrownBy(() -> ListenerTopics.deliver(listener,
                new ConsumerRecord<>(contradiction.topic(), 0, last, contradiction.key(), contradiction.value())))
                .hasStackTraceContaining("ck_rmo_outcome_time_exclusive");
        assertThat(jdbc.queryForMap("""
                SELECT delivery_outcome, delivered_at IS NULL AS no_delivery, failed_at = ? AS failed_as_before
                  FROM rm_orders WHERE order_id = ?
                """, utc(scenario.o2Failed), scenario.o2))
                .as("롤백 — 실패가 그대로 남는다")
                .containsEntry("delivery_outcome", "FAILED").containsEntry("no_delivery", true)
                .containsEntry("failed_as_before", true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Long.class,
                contradiction.eventId()))
                .as("처리됨으로 적히지 않는다 — 재시도·DLQ 의 길로 간다").isZero();
    }

    private void order(String outcome, String status, @Nullable Instant promisedOriginal, Instant promisedRevised,
            @Nullable Instant deliveredAt, @Nullable Instant failedAt) {
        jdbc.update("""
                INSERT INTO rm_orders (order_id, customer_id, order_status, delivery_outcome, camp_id,
                                       promised_end_original, promised_end_revised, delivered_at, failed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Ids.newId(), MARKER, status, outcome, camp, utc(promisedOriginal), utc(promisedRevised),
                utc(deliveredAt), utc(failedAt));
    }

    private void placed(@Nullable UUID campId, String status) {
        jdbc.update("INSERT INTO rm_orders (order_id, customer_id, order_status, camp_id, placed_at) VALUES (?, ?, ?, ?, ?)",
                Ids.newId(), MARKER, status, campId, utc(INTAKE_HOUR.plusSeconds(90)));
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
