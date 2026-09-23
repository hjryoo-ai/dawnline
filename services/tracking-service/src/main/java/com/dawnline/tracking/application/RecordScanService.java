package com.dawnline.tracking.application;

import com.dawnline.common.Ids;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.common.error.ValidationException;
import com.dawnline.tracking.application.port.in.RecordScanUseCase;
import com.dawnline.tracking.application.EtaPropagator.Propagation;
import com.dawnline.tracking.application.port.out.DeliveryEvents;
import com.dawnline.tracking.application.port.out.ShipmentEvents;
import com.dawnline.tracking.application.port.out.ShipmentRepository;
import com.dawnline.tracking.domain.ScanOutcome;
import com.dawnline.tracking.domain.ScanType;
import com.dawnline.tracking.domain.Shipment;
import com.dawnline.tracking.domain.ShipmentEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기사 스캔 적용 (DESIGN.md §5.4).
 *
 * <h2>대상은 주문으로 찾는다 — 번호로 찾지 않는다</h2>
 * {@code orderIds} 로 {@code shipments} 의 PK 를 푼다
 * ([ADR-047](docs/adr/ADR-047-delivery-status-is-a-fact-not-a-revision.md) 결정 1). 요청의
 * {@code routeId}·{@code stopSeq} 는 <strong>확인용</strong>이고, 찾은 배송이 지금 있는 자리와
 * 다르면 <em>그대로 적용하고</em> {@code dawnline_scan_after_relocate_total} 로 센다 — 기사가
 * 개정을 늦게 받은 것이지 스캔이 틀린 것이 아니다.
 *
 * <p>그래서 <strong>편차 전파도 발행도 배송이 지금 있는 좌표에서</strong> 한다. 요청이 말한
 * 번호로 전파하면 {@link EtaPropagator} 가 그 stop 을 찾지 못하고, 옛 좌표를 그대로 실어
 * 보내면 dispatch 의 확인용 컨텍스트가 틀린 값을 받는다. 한 스캔의 주문들이 지금 서로 다른
 * stop 에 있으면 발행이 <strong>두 건</strong>이다 — 그것은 두 지점의 사실이다.
 *
 * <h2>캠프 출발은 라우트의 사건이다</h2>
 * {@code DEPARTED_CAMP} 는 <strong>{@code orderIds} 없이 라우트 전체</strong>에 적용한다(실어
 * 보내면 400 이다) — 기사는 캠프를 한 번 떠나고, 그 순간 그 라우트의 모든 배송이 길 위에 있다.
 * stop 하나만 옮기면 나머지는 {@code SCHEDULED} 로 남아 「아직 출발하지 않은 배송」처럼 보인다.
 * 브로커로는 나가지 않는다: 한 사실을 stop 수만큼 반복해 말하는 것이고, order-service 의 상태
 * 머신은 {@code DISPATCHED} 로 그 구간을 이미 덮는다({@code ScanType.isPublished()}). 운영자가
 * 출발 사실을 화면에서 원하면 라우트 단위 이벤트 하나를 Phase 6 에서 소비자 주도로 정한다.
 *
 * <h2>순서가 규칙이다</h2>
 * 상태를 옮기고 → 편차를 전파하고 → 위험을 판정하고 → 사건을 적재하고 →
 * <strong>마지막에</strong> 센다. 카운터는 트랜잭션을 모르므로
 * 먼저 올리면 뒤의 INSERT 가 실패해 롤백됐을 때 숫자만 남는다 — 「취소 뒤 스캔이 늘었다」는
 * 알림이 실제로는 파티션이 없어서 났다는 뜻이 되고, 그 오해는 대시보드에서 풀리지 않는다.
 *
 * <h2>한 스캔이 주문마다 다른 답을 낼 수 있다</h2>
 * stop 은 여러 주문을 묶고(§6.5 1단계) 취소는 주문 단위다(ADR-026 후속 정정). 그래서 결과가
 * 주문마다 한 줄이다. 합쳐서 하나로 답하면 기사 단말은 「무엇이 안 됐는가」를 알 수 없다.
 *
 * <h2>{@code @Transactional} 이 여기 있어야 하는 이유</h2>
 * 이 유스케이스는 HTTP 요청에서 직접 불린다 — 감싸 주는 {@code IdempotentConsumer} 가 없다.
 * 없으면 상태 갱신과 사건 적재가 서로 다른 트랜잭션이 되어, 「완료로 바뀌었는데 사건은 없는」
 * 행이 남을 수 있다. 어노테이션의 <em>위치</em>는 ArchUnit 규칙 5 가 보지만 <em>존재</em>는 보지
 * 못하므로({@code @Transactional} 이 없어도 규칙은 통과한다) {@code RecordScanServiceTest} 가
 * 직접 확인한다.
 */
public class RecordScanService implements RecordScanUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordScanService.class);

    /** 라우트의 첫 stop. {@code DEPARTED_CAMP} 가 보는 범위의 시작이다. */
    private static final int FIRST_SEQ = 1;

    private final ShipmentRepository shipments;
    private final ShipmentEvents events;
    private final DeliveryEvents delivery;
    private final EtaPropagator eta;
    private final AtRiskDetector atRisk;
    private final TrackingMetrics metrics;
    private final Ids ids;

    /**
     * @param shipments 배송 저장소
     * @param events    사건 적재
     * @param delivery  {@code delivery.status} 발행 (outbox, 불변규칙 1)
     * @param eta       편차 전파 (§5.4)
     * @param atRisk    지연 위험 판정·통지 (§5.4)
     * @param metrics   §9.1 카운터
     * @param ids       UUIDv7 생성기 (불변규칙 10·12)
     */
    public RecordScanService(ShipmentRepository shipments, ShipmentEvents events,
            DeliveryEvents delivery, EtaPropagator eta, AtRiskDetector atRisk,
            TrackingMetrics metrics, Ids ids) {
        this.shipments = Objects.requireNonNull(shipments, "shipments");
        this.events = Objects.requireNonNull(events, "events");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.eta = Objects.requireNonNull(eta, "eta");
        this.atRisk = Objects.requireNonNull(atRisk, "atRisk");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    @Transactional
    public ScanResult record(ScanCommand command) {
        Objects.requireNonNull(command, "command");
        requireReasonOnlyOnFailure(command);
        requireOrdersMatchScope(command);

        boolean fromCamp = command.type() == ScanType.DEPARTED_CAMP;
        List<Shipment> targets = fromCamp
                ? shipments.findByRouteFrom(command.routeId(), FIRST_SEQ)
                : shipments.findAll(command.orderIds());
        if (targets.isEmpty()) {
            // 그 주문들의 배송이 아직 없다(또는 라우트를 잘못 알았다). route.assigned 를 아직
            // 소비하지 않은 창일 수 있으므로 단말은 그대로 재시도하면 된다 — 404 이지 422 가 아니다.
            throw NotFoundException.of(fromCamp ? "Route" : "Shipment",
                    fromCamp ? command.routeId() : command.orderIds());
        }
        List<OrderScan> outcomes = new ArrayList<>(targets.size());
        List<ShipmentEvent> appended = new ArrayList<>(targets.size());
        List<Shipment> moved = new ArrayList<>(targets.size());
        int afterCancel = 0;
        int offPlan = 0;

        for (Shipment shipment : targets) {
            if (!fromCamp && isElsewhere(command, shipment)) {
                // 기사가 개정 r 의 번호로 찍었고 우리는 r+1 을 적용했다. 스캔은 그대로 맞다 —
                // 판정에 쓰지 않고 세기만 한다(카운터는 적재 뒤에 올린다).
                offPlan++;
            }
            ScanOutcome outcome = shipment.recordScan(command.type(), command.occurredAt());
            switch (outcome) {
                case APPLIED -> {
                    shipments.update(shipment);
                    appended.add(eventOf(command, shipment));
                    moved.add(shipment);
                }
                case AFTER_CANCEL -> afterCancel++;
                case STALE -> {
                    // 중복이거나 순서가 뒤바뀐 스캔이다. 사건으로 남기지 않는다 — 남기면
                    // 이 로그를 읽는 사람이 「어느 행이 무언가를 바꿨나」를 알기 위해 상태
                    // 머신을 다시 구현해야 한다.
                }
            }
            outcomes.add(new OrderScan(shipment.orderId(), outcome, shipment.status()));
        }

        // 편차 전파는 상태 전이 <em>뒤</em>다. 순서가 뒤바뀌면 방금 도착한 stop 의 ETA 를
        // 자기 편차로 다시 미는 일이 생긴다. 좌표는 요청이 아니라 «찾은 배송»이 말한다.
        propagate(command, targets);

        events.appendAll(appended);
        publish(command, moved);
        metrics.countScanAfterCancel(afterCancel);
        metrics.countScanAfterRelocate(offPlan);

        // 사유도 좌표도 남기지 않는다 (§9.3 — 고객 식별 정보 금지).
        log.debug("스캔을 적용했다. routeId={}, stopSeq={}, type={}, applied={}, stale={}, "
                        + "afterCancel={}, offPlan={}",
                command.routeId(), command.stopSeq(), command.type(), appended.size(),
                outcomes.size() - appended.size() - afterCancel, afterCancel, offPlan);

        return new ScanResult(command.routeId(), command.stopSeq(), command.type(),
                command.occurredAt(), outcomes);
    }

    /**
     * {@code delivery.status} 를 stop <strong>마다</strong> 한 번 내보낸다 (§4.1).
     *
     * <p>좌표는 요청이 아니라 <strong>배송이 지금 있는 자리</strong>다. 옛 좌표를 실어 보내면
     * dispatch 의 확인용 컨텍스트가 틀린 값을 받고, 저쪽의
     * {@code dawnline_status_after_relocate_total} 이 우리 탓으로 오른다 (ADR-047 결정 1·2).
     * 그래서 옮겨진 주문들이 지금 서로 다른 stop 에 있으면 <strong>두 건</strong>이 나간다 —
     * 한 건으로 합치면 둘 중 하나의 좌표가 거짓이 된다.
     *
     * <p>내보내지 않는 두 경우가 있고 둘 다 「소비자에게 새 사실이 없다」는 같은 이유다.
     * {@code DEPARTED_CAMP} 는 계약의 {@code status} 셋에 없고(라우트의 사건이다,
     * {@link ScanType#isPublished()}), 옮겨진 주문이 없으면 {@code STALE}·{@code AFTER_CANCEL}
     * 뿐이라 상태가 움직이지 않았다.
     */
    private void publish(ScanCommand command, List<Shipment> moved) {
        if (!command.type().isPublished()) {
            return;
        }
        Map<StopKey, List<UUID>> byStop = new LinkedHashMap<>();
        for (Shipment shipment : moved) {
            byStop.computeIfAbsent(StopKey.of(shipment), key -> new ArrayList<>())
                    .add(shipment.orderId());
        }
        byStop.forEach((stop, orderIds) -> delivery.deliveryStatus(stop.routeId(), stop.stopSeq(),
                orderIds, command.type(), command.occurredAt(), command.failureReason()));
    }

    /**
     * 편차를 전파하고 위험을 판정한다 — <strong>찾은 배송이 있는 라우트마다</strong>.
     *
     * <p>기준이 되는 순번은 그 라우트에서 찾은 것 중 <strong>가장 앞</strong>이다. 전파 대상이
     * 「뒤따르는 stop」이므로 가장 앞의 stop 이 가장 넓게 민다. 보통 이 맵은 한 줄이다 — 여러
     * 줄이 되는 것은 한 스캔의 주문들이 지금 다른 라우트에 흩어진, 재배치 직후의 창뿐이다.
     */
    private void propagate(ScanCommand command, List<Shipment> targets) {
        Map<UUID, Integer> scanned = new LinkedHashMap<>();
        for (Shipment shipment : targets) {
            scanned.merge(shipment.routeId(), shipment.stopSeq(), Math::min);
        }
        scanned.forEach((routeId, stopSeq) -> {
            Propagation propagation = eta.propagate(routeId, command.type(), stopSeq,
                    command.occurredAt());
            atRisk.evaluate(routeId, propagation);
            log.debug("편차를 전파했다. routeId={}, stopSeq={}, etaMoved={}, deviationS={}",
                    routeId, stopSeq, propagation.moved().size(),
                    propagation.deviation().toSeconds());
        });
    }

    /** 기사가 찍은 자리와 우리가 아는 자리가 다른가. 판정이 아니라 <em>계량</em>에만 쓴다. */
    private static boolean isElsewhere(ScanCommand command, Shipment shipment) {
        return !shipment.routeId().equals(command.routeId())
                || shipment.stopSeq() != command.stopSeq();
    }

    /** 발행을 묶는 자리. 이 값이 이벤트의 {@code routeId}·{@code stopSeq} 가 된다. */
    private record StopKey(UUID routeId, int stopSeq) {

        static StopKey of(Shipment shipment) {
            return new StopKey(shipment.routeId(), shipment.stopSeq());
        }
    }

    private ShipmentEvent eventOf(ScanCommand command, Shipment shipment) {
        return new ShipmentEvent(ids.newUuid(), shipment.orderId(), shipment.routeId(),
                command.type(), command.occurredAt(), command.lat(), command.lng(),
                command.failureReason());
    }

    /**
     * 주문 목록은 <strong>종류가 정한다</strong> — {@code DEPARTED_CAMP} 는 라우트의 사건이라
     * 열쇠가 라우트이고, 나머지는 송장이 열쇠다 (ADR-047 결정 1).
     *
     * <p>캠프 출발에 실린 목록을 조용히 무시하지 않는 이유는 {@code failureReason} 과 같다:
     * 버리면 단말은 자기가 보낸 것이 사라진 줄 모르고, 「어느 주문이 출발했나」를 목록으로
     * 물은 단말은 라우트 전체를 받은 응답을 오해한다.
     */
    private static void requireOrdersMatchScope(ScanCommand command) {
        boolean fromCamp = command.type() == ScanType.DEPARTED_CAMP;
        if (fromCamp && !command.orderIds().isEmpty()) {
            throw ValidationException.field("orderIds", command.type(),
                    "캠프 출발은 라우트의 사건이라 주문을 싣지 않습니다");
        }
        if (!fromCamp && command.orderIds().isEmpty()) {
            throw ValidationException.field("orderIds", command.type(),
                    "스캔할 주문이 있어야 합니다");
        }
    }

    /**
     * 사유는 실패에만 붙는다. 조용히 버리지 않고 거절하는 이유: 완료 사건에 사유가 적힌 로그는
     * 읽는 사람을 오래 헷갈리게 하고, 버리면 단말은 자기가 보낸 것이 사라진 줄 모른다.
     *
     * <p>{@code value} 에 사유 자체를 담지 않는다 — 자유 텍스트라 개인정보가 섞일 수 있고,
     * 오류 응답과 로그에 그대로 실린다 (§9.3).
     */
    private static void requireReasonOnlyOnFailure(ScanCommand command) {
        if (command.failureReason() != null && command.type() != ScanType.FAILED) {
            throw ValidationException.field("failureReason", command.type(),
                    "사유는 FAILED 스캔에만 붙일 수 있습니다");
        }
    }
}
