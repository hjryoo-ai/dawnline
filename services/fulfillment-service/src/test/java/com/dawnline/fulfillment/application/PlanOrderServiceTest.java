package com.dawnline.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TierSchedule;
import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.application.port.in.PlacedOrderSnapshot;
import com.dawnline.fulfillment.application.port.in.PlanOrderUseCase;
import com.dawnline.fulfillment.application.port.out.FulfillmentEvents;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.FcSelection;
import com.dawnline.fulfillment.domain.FulfillmentOrder;
import com.dawnline.fulfillment.domain.FulfillmentOrderStatus;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.UnserviceableReason;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.Zone;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@code order.placed} → 계획 (§5.2). 특히 <strong>웨이브 편입과 약속 개정</strong>.
 *
 * <p>DB 락이 무엇을 막는지는 {@code FulfillmentPersistenceIT} 가 실물로 본다. 여기서 보는 것은
 * 그 위의 판단이다 — 어느 웨이브로 가는가, 언제 개정으로 표시되는가, 무엇을 무시하는가.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("PlanOrderService — 편입과 약속 개정")
class PlanOrderServiceTest {

    /** 2026-09-06 09:00 KST. SAME_DAY 컷오프 10:00 이 아직 남아 있다. */
    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
    private static final Instant CUTOFF_10 = Instant.parse("2026-09-06T01:00:00Z");
    private static final Instant CUTOFF_14 = Instant.parse("2026-09-06T05:00:00Z");

    private static final UUID CAMP_ID = UUID.randomUUID();
    private static final UUID ZONE_ID = UUID.randomUUID();
    private static final UUID HOME_FC = UUID.randomUUID();
    private static final String GEOHASH7 = "wydm7bc";

    private final InMemoryFulfillmentRepositories repositories = new InMemoryFulfillmentRepositories();
    private final RecordingEvents events = new RecordingEvents();
    private final TierSchedule schedule = TierSchedule.standard();

    private PlanOrderService service;

    @BeforeEach
    void setUp() {
        repositories.addCamp(new Camp(CAMP_ID, "CAMP-A", HOME_FC, new GeoPoint(37.50, 127.00), true));
        repositories.addZone(new Zone(ZONE_ID, "wydm7", CAMP_ID));
        repositories.addCenter(InMemoryFulfillmentRepositories.center(
                HOME_FC, "FC-A", 37.51, 127.01, true, Set.of(ServiceTier.values())));
        service = newService(NOW);
    }

    /** UUIDv7 생성기. 이 테스트는 id 값을 보지 않으므로 시스템 시계로 충분하다. */
    private static Ids ids() {
        return new Ids(Clock.systemUTC(), java.util.random.RandomGenerator.getDefault());
    }

    private PlanOrderService newService(Instant now) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return new PlanOrderService(
                repositories.referenceData(),
                new FcCandidateAssembler(repositories.referenceData(),
                        (camp, fcIds) -> java.util.Map.of(HOME_FC, 1.4)),
                new FcSelection(clock, Duration.ofHours(24)),
                repositories.waveRepository(),
                repositories.orderRepository(),
                events,
                schedule,
                ids(),
                clock);
    }

    private static PlacedOrderSnapshot snapshot(Instant cutoffAt) {
        return snapshot(UUID.randomUUID(), cutoffAt, GEOHASH7);
    }

    private static PlacedOrderSnapshot snapshot(UUID orderId, Instant cutoffAt, String geohash7) {
        return new PlacedOrderSnapshot(orderId, UUID.randomUUID(), "SAME_DAY",
                new PlacedOrderSnapshot.Address("서울 강남구 테헤란로 1", "06236",
                        new GeoPoint(37.4979, 127.0276), geohash7),
                new TimeWindow(cutoffAt, cutoffAt.plus(Duration.ofHours(6))),
                new PlacedOrderSnapshot.Parcel(1200, 8000, false, false),
                List.of(new PlacedOrderSnapshot.Item("SKU-00001", 1)),
                cutoffAt.minus(Duration.ofHours(1)),
                cutoffAt);
    }

    // --- 정상 편입 -------------------------------------------------------------

    @Test
    void 웨이브가_없으면_만들고_편입한다() {
        PlacedOrderSnapshot snapshot = snapshot(CUTOFF_10);

        PlanOrderUseCase.PlanOutcome outcome = service.plan(snapshot, UUID.randomUUID());

        assertThat(outcome.kind()).isEqualTo(PlanOrderUseCase.PlanOutcome.Kind.PLANNED);
        assertThat(repositories.waves()).singleElement()
                .satisfies(wave -> {
                    assertThat(wave.campId()).isEqualTo(CAMP_ID);
                    assertThat(wave.cutoffAt()).isEqualTo(CUTOFF_10);
                    assertThat(wave.orderCount()).as("편입은 카운트를 올리지 않는다 (ADR-025)").isZero();
                });
        assertThat(events.planned).hasSize(1);
    }

    @Test
    void 같은_컷오프의_두_주문은_한_웨이브에_들어간다() {
        service.plan(snapshot(CUTOFF_10), UUID.randomUUID());
        service.plan(snapshot(CUTOFF_10), UUID.randomUUID());

        assertThat(repositories.waves()).hasSize(1);
    }

    @Test
    void 계획된_주문은_판정_결과를_행에_남긴다() {
        PlacedOrderSnapshot snapshot = snapshot(CUTOFF_10);
        UUID eventId = UUID.randomUUID();

        service.plan(snapshot, eventId);

        FulfillmentOrder saved = repositories.order(snapshot.orderId()).orElseThrow();
        assertThat(saved.status()).isEqualTo(FulfillmentOrderStatus.PLANNED);
        assertThat(saved.fcId()).contains(HOME_FC);
        assertThat(saved.campId()).contains(CAMP_ID);
        assertThat(saved.zoneId()).contains(ZONE_ID);
        assertThat(saved.placedEventId()).contains(eventId);
        assertThat(saved.promiseRevised()).isFalse();
    }

    // --- 약속 개정 (ADR-020 결정 3) --------------------------------------------

    @Test
    void 마감된_웨이브의_컷오프를_가진_주문은_다음_웨이브로_밀리고_개정된다() {
        // grace 를 넘겨 도착했다. 조용히 밀지 않는 것이 이 경로의 요점이다.
        Wave closed = Wave.open(ids().newUuid(), CAMP_ID, ServiceTier.SAME_DAY, CUTOFF_10);
        closed.beginClosing();
        repositories.waveRepository().insertIfAbsent(closed);
        repositories.waveRepository().update(closed);

        PlacedOrderSnapshot snapshot = snapshot(CUTOFF_10);
        PlanOrderUseCase.PlanOutcome outcome = service.plan(snapshot, UUID.randomUUID());

        assertThat(outcome.revised()).isTrue();
        FulfillmentOrder saved = repositories.order(snapshot.orderId()).orElseThrow();
        assertThat(saved.cutoffAt()).contains(CUTOFF_14);
        assertThat(saved.promiseRevised()).isTrue();
    }

    @Test
    void 개정된_약속창은_공유_표에서_나온다() {
        // §2.2 표를 이 서비스에 다시 적지 않는다 (ADR-020 후속 정정 2).
        Wave closed = Wave.open(ids().newUuid(), CAMP_ID, ServiceTier.SAME_DAY, CUTOFF_10);
        closed.beginClosing();
        repositories.waveRepository().insertIfAbsent(closed);
        repositories.waveRepository().update(closed);

        service.plan(snapshot(CUTOFF_10), UUID.randomUUID());

        TimeWindow expected = schedule.windowFor("SAME_DAY", CUTOFF_14);
        assertThat(events.planned).singleElement()
                .satisfies(sent -> {
                    assertThat(sent.window()).isEqualTo(expected);
                    assertThat(sent.revised()).isTrue();
                });
    }

    @Test
    void 밀리지_않은_주문은_접수_시점의_약속을_그대로_쓴다() {
        PlacedOrderSnapshot snapshot = snapshot(CUTOFF_10);

        service.plan(snapshot, UUID.randomUUID());

        assertThat(events.planned).singleElement()
                .satisfies(sent -> {
                    assertThat(sent.window()).isEqualTo(snapshot.promisedWindow());
                    assertThat(sent.revised()).isFalse();
                });
    }

    // --- 배차 불가 -------------------------------------------------------------

    @Test
    void 권역을_못_찾으면_NO_ZONE_MATCH_다() {
        PlacedOrderSnapshot snapshot = snapshot(UUID.randomUUID(), CUTOFF_10, "zzzzzbc");

        PlanOrderUseCase.PlanOutcome outcome = service.plan(snapshot, UUID.randomUUID());

        assertThat(outcome.reason()).contains(UnserviceableReason.NO_ZONE_MATCH);
        assertThat(outcome.campId()).isEmpty();
        assertThat(events.unserviceable).singleElement()
                .extracting(Rejected::reason).isEqualTo(UnserviceableReason.NO_ZONE_MATCH);
    }

    @Test
    void 배차_불가도_행과_이벤트를_남긴다() {
        // 배차하지 못한 것도 하류가 알아야 하는 사실이다. 조용히 끝내지 않는다 (§5.2 6단계).
        PlacedOrderSnapshot snapshot = snapshot(UUID.randomUUID(), CUTOFF_10, "zzzzzbc");

        service.plan(snapshot, UUID.randomUUID());

        assertThat(repositories.order(snapshot.orderId())).get()
                .extracting(FulfillmentOrder::status).isEqualTo(FulfillmentOrderStatus.UNSERVICEABLE);
        assertThat(events.unserviceable).hasSize(1);
    }

    @Test
    void 하루_넘은_컷오프는_STALE_PLACED_다() {
        // ADR-020 후속 정정. 다음 웨이브를 찾아 봐야 유령 배송이다.
        Instant longAgo = NOW.minus(Duration.ofDays(3));
        PlacedOrderSnapshot snapshot = snapshot(longAgo);

        PlanOrderUseCase.PlanOutcome outcome = service.plan(snapshot, UUID.randomUUID());

        assertThat(outcome.reason()).contains(UnserviceableReason.STALE_PLACED);
        assertThat(repositories.waves()).as("웨이브를 만들지 않는다").isEmpty();
    }

    // --- 순서 뒤바뀜 -----------------------------------------------------------

    @Test
    void 취소_선착_뒤에_온_주문은_무시한다() {
        PlacedOrderSnapshot snapshot = snapshot(CUTOFF_10);
        repositories.orderRepository().insertIfAbsent(
                FulfillmentOrder.cancelledBeforePlaced(snapshot.orderId(), NOW));

        PlanOrderUseCase.PlanOutcome outcome = service.plan(snapshot, UUID.randomUUID());

        assertThat(outcome.kind()).isEqualTo(PlanOrderUseCase.PlanOutcome.Kind.IGNORED);
        assertThat(events.planned).isEmpty();
        assertThat(events.unserviceable).isEmpty();
        assertThat(repositories.order(snapshot.orderId())).get()
                .extracting(FulfillmentOrder::status).isEqualTo(FulfillmentOrderStatus.CANCELLED);
    }

    @Test
    void 이미_계획된_주문이_다시_와도_두_번_계획하지_않는다() {
        PlacedOrderSnapshot snapshot = snapshot(CUTOFF_10);
        service.plan(snapshot, UUID.randomUUID());

        PlanOrderUseCase.PlanOutcome again = service.plan(snapshot, UUID.randomUUID());

        assertThat(again.kind()).isEqualTo(PlanOrderUseCase.PlanOutcome.Kind.IGNORED);
        assertThat(events.planned).hasSize(1);
        assertThat(repositories.waves()).hasSize(1);
    }

    /** 발행된 이벤트를 기록한다. */
    private static final class RecordingEvents implements FulfillmentEvents {

        private final List<Planned> planned = new ArrayList<>();
        private final List<Rejected> unserviceable = new ArrayList<>();

        @Override
        public void planned(PlacedOrderSnapshot snapshot, UUID fcId, UUID campId, UUID zoneId,
                UUID waveId, Instant waveCutoffAt, TimeWindow window, boolean revised) {
            planned.add(new Planned(snapshot.orderId(), fcId, waveId, waveCutoffAt, window, revised));
        }

        @Override
        public void unserviceable(PlacedOrderSnapshot snapshot, UnserviceableReason reason) {
            unserviceable.add(new Rejected(snapshot.orderId(), reason));
        }

        @Override
        public void waveClosed(Wave wave) {
            throw new UnsupportedOperationException("이 테스트의 관심이 아니다");
        }
    }

    private record Planned(UUID orderId, UUID fcId, UUID waveId, Instant waveCutoffAt,
            TimeWindow window, boolean revised) {
    }

    private record Rejected(UUID orderId, UnserviceableReason reason) {
    }
}
