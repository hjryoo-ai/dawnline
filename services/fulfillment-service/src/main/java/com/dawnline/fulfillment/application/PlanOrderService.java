package com.dawnline.fulfillment.application;

import com.dawnline.common.Ids;
import com.dawnline.common.TierSchedule;
import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.application.port.in.PlacedOrderSnapshot;
import com.dawnline.fulfillment.application.port.in.PlanOrderUseCase;
import com.dawnline.fulfillment.application.port.out.FulfillmentEvents;
import com.dawnline.fulfillment.application.port.out.FulfillmentOrderRepository;
import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.fulfillment.application.port.out.WaveRepository;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.CandidateFc;
import com.dawnline.fulfillment.domain.FcSelection;
import com.dawnline.fulfillment.domain.FcSelectionResult;
import com.dawnline.fulfillment.domain.FulfillmentOrder;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.UnserviceableReason;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.Zone;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code order.placed} → FC·캠프·권역·웨이브 결정 (§5.2).
 *
 * <h2>한 트랜잭션이다</h2>
 * 주문 행 INSERT, 웨이브 편입, {@code fulfillment.planned} outbox 기록이 모두 같은 트랜잭션에
 * 들어간다(불변규칙 1). 나누면 "행은 있는데 이벤트가 없다" 또는 그 반대가 생긴다.
 *
 * <h2>편입은 웨이브 행을 공유 잠금만 한다 (ADR-025)</h2>
 * 이 서비스가 웨이브에 요구하는 것은 <em>내가 주문을 넣는 동안 마감되지 않는다</em> 하나뿐이다.
 * 그래서 {@code FOR SHARE} 로 잡고 상태만 확인한다. 같은 웨이브로 몰리는 주문들은 서로를 막지
 * 않으며, 마감이 그 공유 락을 기다렸다가 {@code CLOSING} 으로 바꾼다.
 *
 * <h2>마감된 웨이브를 만나면 다음 컷오프로 민다</h2>
 * grace 를 넘겨 도착한 주문이다(ADR-020 결정 3). 다음 컷오프와 그 배송창은 {@code libs/common} 의
 * {@link TierSchedule} 이 준다 — §2.2 표를 여기에 다시 적지 않는다(ADR-020 후속 정정 2).
 * 그렇게 밀린 주문은 {@code promiseRevised: true} 로 나가고, order-service 가 그것을 받아 고객의
 * 약속을 갱신한다. <strong>조용히 밀지 않는 것이 이 경로의 요점이다.</strong>
 */
public class PlanOrderService implements PlanOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(PlanOrderService.class);

    /**
     * 한 주문이 밀릴 수 있는 최대 웨이브 수.
     *
     * <p>실제로는 한 번이면 끝난다 — 다음 웨이브는 아직 열려 있다. 상한을 두는 이유는 시계나
     * 시드가 어긋났을 때 이 루프가 <em>영원히</em> 도는 것을 막기 위해서다. 상한에 걸리면
     * {@code STALE_PLACED} 로 끝낸다 — 그것이 "이 주문을 오늘 일로 볼 수 없다" 의 뜻이다.
     */
    private static final int MAX_WAVE_PUSHES = 3;

    private final ReferenceData referenceData;
    private final FcCandidateAssembler candidates;
    private final FcSelection selection;
    private final WaveRepository waves;
    private final FulfillmentOrderRepository orders;
    private final FulfillmentEvents events;
    private final TierSchedule schedule;
    private final Ids ids;
    private final Clock clock;

    /**
     * @param referenceData 권역·캠프 조회
     * @param candidates    후보 조립 (카탈로그 + 거리 + 재고)
     * @param selection     판정 순수 함수
     * @param waves         웨이브 저장소
     * @param orders        주문 저장소
     * @param events        outbox 발행
     * @param schedule      §2.2 컷오프·배송창 표 (공유)
     * @param ids           UUIDv7 생성기 (불변규칙 10)
     * @param clock         시각 출처 (불변규칙 12)
     */
    public PlanOrderService(ReferenceData referenceData, FcCandidateAssembler candidates,
            FcSelection selection, WaveRepository waves, FulfillmentOrderRepository orders,
            FulfillmentEvents events, TierSchedule schedule, Ids ids, Clock clock) {
        this.referenceData = Objects.requireNonNull(referenceData, "referenceData");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.selection = Objects.requireNonNull(selection, "selection");
        this.waves = Objects.requireNonNull(waves, "waves");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.events = Objects.requireNonNull(events, "events");
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional
    public PlanOutcome plan(PlacedOrderSnapshot snapshot, UUID placedEventId) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(placedEventId, "placedEventId");

        // 이미 판정된 주문인가. 중복은 processed_events 가 앞에서 거르므로, 여기까지 오는 것은
        // 다른 eventId 로 같은 주문이 다시 온 경우다 — 취소 선착이 대표적이다 (ADR-022).
        Optional<FulfillmentOrder> existing = orders.findById(snapshot.orderId());
        if (existing.isPresent() && existing.get().ignoresPlaced()) {
            return PlanOutcome.ignored(null);
        }

        Optional<Zone> zone = referenceData.findZone(snapshot.address().geohash5());
        if (zone.isEmpty()) {
            // 서비스하지 않는 지역이다. 시드가 지오코더의 출력을 덮지 못해서가 아니어야 하고,
            // 그것은 ZoneSeedCoverageIT 가 지킨다 (ADR-021).
            return reject(snapshot, placedEventId, UnserviceableReason.NO_ZONE_MATCH, null);
        }
        Optional<Camp> camp = referenceData.findCamp(zone.get().campId());
        if (camp.isEmpty()) {
            return reject(snapshot, placedEventId, UnserviceableReason.NO_ACTIVE_CAMP, null);
        }

        List<CandidateFc> fcs = candidates.forCamp(camp.get(), snapshot.toOrderToPlan().lines());
        FcSelectionResult result = selection.select(snapshot.toOrderToPlan(), camp.get(), fcs);

        return switch (result) {
            case FcSelectionResult.Unserviceable unserviceable ->
                    reject(snapshot, placedEventId, unserviceable.reason(), camp.get().id());
            case FcSelectionResult.Selected selected ->
                    admit(snapshot, placedEventId, camp.get(), zone.get(), selected);
        };
    }

    /** 웨이브에 편입하고 {@code fulfillment.planned} 를 낸다. */
    private PlanOutcome admit(PlacedOrderSnapshot snapshot, UUID placedEventId, Camp camp, Zone zone,
            FcSelectionResult.Selected selected) {

        ServiceTier tier = ServiceTier.valueOf(snapshot.serviceTier());
        Optional<Wave> admitted = openWaveFor(camp, tier, snapshot.cutoffAt());
        if (admitted.isEmpty()) {
            // 밀 수 있는 웨이브를 못 찾았다 = 이 주문을 오늘 일로 볼 수 없다.
            return reject(snapshot, placedEventId, UnserviceableReason.STALE_PLACED, camp.id());
        }

        Wave wave = admitted.get();
        boolean revised = !wave.cutoffAt().equals(snapshot.cutoffAt());
        TimeWindow window = revised
                ? schedule.windowFor(snapshot.serviceTier(), wave.cutoffAt())
                : snapshot.promisedWindow();

        Instant now = clock.instant();
        FulfillmentOrder order = FulfillmentOrder.planned(snapshot.orderId(), placedEventId, wave.id(),
                camp.id(), selected.fc().id(), zone.id(), wave.cutoffAt(), window, revised,
                selected.fallbackReason(), now);

        if (!orders.insertIfAbsent(order)) {
            // 그 틈에 다른 리스너가 행을 만들었다. PK 가 직렬화했고 진 쪽이 우리다 (ADR-022 결정 4).
            log.debug("이미 행이 있어 계획을 적용하지 않습니다. orderId={}", snapshot.orderId());
            return PlanOutcome.ignored(null);
        }

        events.planned(snapshot, selected.fc().id(), camp.id(), zone.id(), wave.id(),
                wave.cutoffAt(), window, revised);
        return PlanOutcome.planned(wave.id(), camp.id(), revised);
    }

    /**
     * 주문을 받을 수 있는 웨이브를 찾거나 만든다.
     *
     * <p>없으면 만들고({@code ON CONFLICT DO NOTHING} 후 재조회), 공유 잠금으로 상태를 확인한다.
     * 이미 마감 중이면 다음 컷오프로 민다 — 그 순간부터 이 주문은 <em>개정된 약속</em>을 받는다.
     */
    private Optional<Wave> openWaveFor(Camp camp, ServiceTier tier, Instant cutoffAt) {
        Instant target = cutoffAt;
        for (int push = 0; push <= MAX_WAVE_PUSHES; push++) {
            if (selection.isStale(target)) {
                // 컷오프가 상한을 넘겼다. 다음 웨이브를 찾아 봐야 유령 배송이다 (ADR-020 후속 정정).
                return Optional.empty();
            }
            Wave wave = findOrCreate(camp, tier, target);
            // FOR SHARE — 이 트랜잭션이 끝날 때까지 이 웨이브는 마감될 수 없다 (ADR-025).
            Optional<Wave> locked = waves.findByIdForShare(wave.id());
            if (locked.isPresent() && locked.get().acceptsOrders()) {
                return locked;
            }
            target = schedule.nextCutoffAfter(tier.name(), target);
        }
        log.warn("웨이브를 {}번 밀어도 열린 웨이브를 찾지 못했습니다. camp={} tier={} cutoffAt={}",
                MAX_WAVE_PUSHES, camp.code(), tier, cutoffAt);
        return Optional.empty();
    }

    private Wave findOrCreate(Camp camp, ServiceTier tier, Instant cutoffAt) {
        return waves.findByNaturalKey(camp.id(), tier, cutoffAt).orElseGet(() -> {
            waves.insertIfAbsent(Wave.open(ids.newUuid(), camp.id(), tier, cutoffAt));
            // 졌더라도 이긴 쪽의 행이 있다. UNIQUE (camp_id, service_tier, cutoff_at) 가 그것을 보장한다.
            return waves.findByNaturalKey(camp.id(), tier, cutoffAt).orElseThrow(() ->
                    new IllegalStateException("웨이브를 만들지도 찾지도 못했습니다: camp=" + camp.code()));
        });
    }

    /** 배차 불가로 종결하고 사유를 하류로 보낸다 (§5.2 6단계). */
    private PlanOutcome reject(PlacedOrderSnapshot snapshot, UUID placedEventId,
            UnserviceableReason reason, UUID campId) {

        FulfillmentOrder order = FulfillmentOrder.unserviceable(
                snapshot.orderId(), placedEventId, reason, campId, clock.instant());
        if (!orders.insertIfAbsent(order)) {
            return PlanOutcome.ignored(null);
        }
        events.unserviceable(snapshot, reason);
        return PlanOutcome.unserviceable(reason, campId);
    }
}
