package com.dawnline.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TierSchedule;
import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.application.FulfillmentMetrics;
import com.dawnline.fulfillment.application.port.in.PlacedOrderSnapshot;
import com.dawnline.fulfillment.application.port.in.PlanOrderUseCase;
import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.web.internal.InternalToken;
import com.dawnline.web.internal.InternalTokens;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영자 조기 마감 — HTTP 에서 DB 와 outbox 까지, 그리고 그 대가 (DESIGN.md §5.2, ADR-054).
 *
 * <p>둘을 본다.
 * <ol>
 *   <li><strong>표면</strong>: 200 · 400 · 404 · 409, 저장된 {@code close_cause}, outbox 의 {@code wave.closed}.</li>
 *   <li><strong>대가</strong>: 컷오프 전에 닫힌 뒤 같은 {@code cutoffAt} 으로 온 주문은 다음 웨이브로 가고
 *       {@code promiseRevised} 이며 {@code cause="manual"} 로 세어진다. ADR-054 「맥락」의 관측 근거가 이것이다.</li>
 * </ol>
 *
 * <p>{@code wave.closed} 가 <em>브로커까지</em> 가는 것은 {@code WaveLifecycleIT} 가 본다 — 본문이 스케줄러와
 * 같은 코드이고, 여기서 릴레이를 켜면 그 IT 와 리더를 다툰다(CLAUDE.md 「공유 자원을 쓰는 IT 는 자기 자리에서
 * 켜고 끈다」). 여기서는 outbox 행까지만 본다.
 */
@SpringBootTest(classes = FulfillmentApplication.class)
@AutoConfigureMockMvc
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("WaveEarlyCloseIT — 컷오프를 앞당기면 약속이 개정된다")
class WaveEarlyCloseIT extends FulfillmentIntegrationTestBase {

    /** 시드된 캠프 (R__seed_fulfillment) — {@code wave.closed} 가 캠프 좌표를 실어야 한다. */
    private static final UUID SEEDED_CAMP = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PlanOrderUseCase planOrder;

    @Autowired
    private ReferenceData referenceData;

    @Autowired
    private TierSchedule schedule;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    /**
     * 발행을 보지 않는 IT 는 자기 자리에서 릴레이를 끈다 — 켜 두면 {@code WaveLifecycleIT} 와 advisory lock 을
     * 다툰다. 편입 경로의 거리 계산은 Redis 가 없어도 DB 로 폴백하지만(불변규칙 7) 여기서 볼 것이 아니므로
     * 기반의 Redis 를 그대로 쓴다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void 릴레이를_끈다(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    /** 이 클래스가 outbox 에 행을 남긴 애그리거트 — 웨이브와 주문. */
    private final List<UUID> aggregates = new ArrayList<>();

    /**
     * 남긴 outbox 행을 지운다.
     *
     * <p>릴레이를 껐으므로 이 클래스의 행은 발행되지 않은 채 공유 DB 에 남는다. 그러면 <strong>다른 IT 의
     * 릴레이가 그것을 집는다</strong> — {@code created_at} 순서의 맨 앞에서, 그 IT 가 만들지 않은 토픽
     * ({@code wave.closed})으로. 자동 토픽 생성이 꺼져 있어 전송은 일시적 실패로 끝나고, 릴레이는 그 배치를
     * 멈추므로(§4.6) 뒤에 선 그 IT 자신의 행이 나가지 못한다. 실제로 {@code FulfillmentPublishIT} 가 60초를
     * 기다리다 이 클래스의 주문 하나만 받고 실패했다(2026-09-24). 되돌리지 말고 만들고 지운다(CLAUDE.md).
     */
    @AfterEach
    void 남긴_outbox_행을_지운다() {
        if (aggregates.isEmpty()) {
            return;
        }
        tx().executeWithoutResult(status -> entityManager
                .createNativeQuery("DELETE FROM outbox_events WHERE aggregate_id IN (?1)")
                .setParameter(1, aggregates).executeUpdate());
    }

    // --- 표면 -----------------------------------------------------------------

    @Test
    void 컷오프_전에_닫으면_200_이고_MANUAL_로_저장되고_wave_closed_가_outbox_에_들어간다() throws Exception {
        UUID waveId = openWave(SEEDED_CAMP, futureCutoff());

        close(waveId, "물량 조기 소진")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waveId").value(waveId.toString()))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closeCause").value("MANUAL"));

        Object[] row = single("SELECT status, close_cause, closed_at < cutoff_at FROM waves WHERE id = ?1", waveId);
        assertThat(row[0]).isEqualTo("CLOSED");
        assertThat(row[1]).isEqualTo("MANUAL");
        assertThat(row[2]).as("컷오프 전에 닫혔다 — 이 커맨드의 뜻").isEqualTo(true);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?1 AND event_type = 'wave.closed'",
                waveId)).as("스케줄러와 같은 본문 — wave.closed 가 같은 트랜잭션에서 outbox 에 들어갔다").isEqualTo(1);
    }

    @Test
    void 다시_누르면_409_wave_not_open_이고_누가_닫았는지_말하며_두_번째는_적용되지_않는다() throws Exception {
        UUID waveId = openWave(SEEDED_CAMP, futureCutoff());
        close(waveId, "첫 요청").andExpect(status().isOk());

        close(waveId, "응답을 못 받아 다시 누름")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("wave-not-open"))
                .andExpect(jsonPath("$.currentState").value("CLOSED"))
                .andExpect(jsonPath("$.closeCause").value("MANUAL"));

        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?1 AND event_type = 'wave.closed'",
                waveId)).as("wave.closed 가 두 번이면 하류가 두 번 계획한다").isEqualTo(1);
    }

    @Test
    void 이유가_없으면_400_이고_웨이브는_열린_채다() throws Exception {
        UUID waveId = openWave(SEEDED_CAMP, futureCutoff());

        mvc.perform(post("/api/v1/waves/{waveId}/close", waveId)
                        .header(InternalToken.HEADER, InternalTokens.TEST_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"));

        assertThat(single("SELECT status, close_cause FROM waves WHERE id = ?1", waveId))
                .containsExactly("OPEN", null);
    }

    @Test
    void 없는_웨이브는_404_다() throws Exception {
        close(Ids.newId(), "r")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void DB_가_원인_없는_마감과_마감_없는_원인을_거절한다() {
        // V3 의 CHECK — 「언제 닫혔나」와 「누가 닫았나」는 함께 있거나 함께 없다. 도메인도 같은 문장을
        // 갖지만(Wave 생성자) 그것은 읽는 시점이고, 이것은 쓰는 시점이다.
        UUID waveId = openWave(SEEDED_CAMP, futureCutoff());

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> entityManager.createNativeQuery(
                        "UPDATE waves SET status = 'CLOSED', closed_at = now() WHERE id = ?1")
                .setParameter(1, waveId).executeUpdate()))
                .hasStackTraceContaining("ck_waves_close_cause_with_closed_at");
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> entityManager.createNativeQuery(
                        "UPDATE waves SET close_cause = 'MANUAL' WHERE id = ?1")
                .setParameter(1, waveId).executeUpdate()))
                .hasStackTraceContaining("ck_waves_close_cause_with_closed_at");
    }

    // --- 대가 (ADR-054 맥락의 관측) ---------------------------------------------

    @Test
    void 닫힌_뒤_같은_컷오프로_온_주문은_다음_웨이브로_가고_manual_로_개정된다() throws Exception {
        Instant cutoffAt = futureCutoff();
        String geohash7 = anyZoneGeohash5() + "bc";

        // 닫기 전 — 약속받은 웨이브에 그대로 들어간다.
        PlacedOrderSnapshot first = snapshot(geohash7, cutoffAt);
        aggregates.add(first.orderId());
        PlanOrderUseCase.PlanOutcome before = planOrder.plan(first, Ids.newId());
        assertThat(before.kind()).as("전제 — 시드된 권역이라 계획된다")
                .isEqualTo(PlanOrderUseCase.PlanOutcome.Kind.PLANNED);
        assertThat(before.revised()).as("전제 — 닫기 전에는 개정이 없다").isFalse();
        UUID waveId = before.waveId().orElseThrow();
        aggregates.add(waveId);
        String campCode = referenceData.findCamp(before.campId().orElseThrow()).orElseThrow().code();
        double manualBefore = revised(campCode, "manual");
        double scheduledBefore = revised(campCode, "scheduled");

        close(waveId, "컷오프 앞당김").andExpect(status().isOk());

        // 닫은 뒤 — order-service 는 이 웨이브가 닫힌 것을 모르고 같은 컷오프를 계속 약속한다.
        PlacedOrderSnapshot late = snapshot(geohash7, cutoffAt);
        aggregates.add(late.orderId());
        PlanOrderUseCase.PlanOutcome after = planOrder.plan(late, Ids.newId());

        assertThat(after.revised()).as("약속을 받자마자 개정된다 — 조기 마감의 대가").isTrue();
        assertThat(after.waveId()).isPresent().get().isNotEqualTo(waveId);
        Object[] order = single(
                "SELECT cutoff_at, promise_revised FROM fulfillment_orders WHERE order_id = ?1", late.orderId());
        assertThat((Instant) order[0])
                .isEqualTo(schedule.nextCutoffAfter("SAME_DAY", cutoffAt));
        assertThat(order[1]).isEqualTo(true);
        assertThat(revised(campCode, "manual") - manualBefore)
                .as("원인은 원래 컷오프 웨이브의 close_cause 다 (ADR-054 결정 4)").isEqualTo(1.0);
        assertThat(revised(campCode, "scheduled") - scheduledBefore)
                .as("사람이 누른 결과가 「grace 가 모자라다」로 읽히지 않는다").isZero();
    }

    // --- 도우미 ---------------------------------------------------------------

    private ResultActions close(UUID waveId, String reason) throws Exception {
        return mvc.perform(post("/api/v1/waves/{waveId}/close", waveId)
                .header(InternalToken.HEADER, InternalTokens.TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}"));
    }

    /**
     * 이 테스트의 컷오프 — 주입된 시계에서 뽑는다(CLAUDE.md — 값이 {@code FcSelection.isStale} 에서 시계와
     * 비교된다). 테스트마다 다른 값이라 자연키가 겹치지 않고, 마이크로초로 잘라 저장 정밀도와 맞춘다.
     */
    private Instant futureCutoff() {
        return clock.instant().plus(Duration.ofHours(2)).plusMillis(Ids.newId().getLeastSignificantBits() & 0xFFFFF)
                .truncatedTo(ChronoUnit.MICROS);
    }

    private UUID openWave(UUID campId, Instant cutoffAt) {
        UUID id = Ids.newId();
        tx().executeWithoutResult(status -> entityManager.createNativeQuery("""
                        INSERT INTO waves (id, camp_id, service_tier, cutoff_at, status, order_count, version)
                        VALUES (?1, ?2, 'SAME_DAY', ?3, 'OPEN', 0, 0)""")
                .setParameter(1, id).setParameter(2, campId).setParameter(3, cutoffAt).executeUpdate());
        aggregates.add(id);
        return id;
    }

    private PlacedOrderSnapshot snapshot(String geohash7, Instant cutoffAt) {
        return new PlacedOrderSnapshot(Ids.newId(), Ids.newId(), "SAME_DAY",
                new PlacedOrderSnapshot.Address("서울 강남구 테헤란로 1", "06236",
                        new GeoPoint(37.4979, 127.0276), geohash7),
                new TimeWindow(cutoffAt, cutoffAt.plus(Duration.ofHours(6))),
                new PlacedOrderSnapshot.Parcel(1200, 8000, false, false),
                List.of(new PlacedOrderSnapshot.Item("SKU-00001", 1)),
                clock.instant(), cutoffAt);
    }

    private String anyZoneGeohash5() {
        return tx().execute(status -> ((String) entityManager
                .createNativeQuery("SELECT geohash5 FROM zones ORDER BY geohash5 LIMIT 1")
                .getSingleResult()).strip());
    }

    private double revised(String campCode, String cause) {
        Counter counter = meters.find(FulfillmentMetrics.PROMISE_REVISED)
                .tag("camp", campCode).tag("tier", "SAME_DAY").tag("cause", cause).counter();
        return counter == null ? 0 : counter.count();
    }

    private Object[] single(String sql, UUID id) {
        return tx().execute(status -> (Object[]) entityManager.createNativeQuery(sql)
                .setParameter(1, id).getSingleResult());
    }

    private long count(String sql, UUID id) {
        return tx().execute(status -> ((Number) entityManager.createNativeQuery(sql)
                .setParameter(1, id).getSingleResult()).longValue());
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
