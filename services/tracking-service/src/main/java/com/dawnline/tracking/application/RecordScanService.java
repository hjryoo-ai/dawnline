package com.dawnline.tracking.application;

import com.dawnline.common.Ids;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.common.error.ValidationException;
import com.dawnline.tracking.application.port.in.RecordScanUseCase;
import com.dawnline.tracking.application.port.out.ShipmentEvents;
import com.dawnline.tracking.application.port.out.ShipmentRepository;
import com.dawnline.tracking.domain.ScanOutcome;
import com.dawnline.tracking.domain.ScanType;
import com.dawnline.tracking.domain.Shipment;
import com.dawnline.tracking.domain.ShipmentEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기사 스캔 적용 (DESIGN.md §5.4).
 *
 * <h2>순서가 규칙이다</h2>
 * 상태를 옮기고 → 사건을 적재하고 → <strong>마지막에</strong> 센다. 카운터는 트랜잭션을 모르므로
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

    private final ShipmentRepository shipments;
    private final ShipmentEvents events;
    private final TrackingMetrics metrics;
    private final Ids ids;

    /**
     * @param shipments 배송 저장소
     * @param events    사건 적재
     * @param metrics   §9.1 카운터
     * @param ids       UUIDv7 생성기 (불변규칙 10·12)
     */
    public RecordScanService(ShipmentRepository shipments, ShipmentEvents events,
            TrackingMetrics metrics, Ids ids) {
        this.shipments = Objects.requireNonNull(shipments, "shipments");
        this.events = Objects.requireNonNull(events, "events");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    @Transactional
    public ScanResult record(ScanCommand command) {
        Objects.requireNonNull(command, "command");
        requireReasonOnlyOnFailure(command);

        List<Shipment> atStop = shipments.findByRouteAndStop(command.routeId(), command.stopSeq());
        if (atStop.isEmpty()) {
            // 라우트를 잘못 알았거나 순번이 틀렸다. 아직 route.assigned 를 소비하지 않은 창일
            // 수도 있으므로 단말은 그대로 재시도하면 된다 — 그래서 404 이지 422 가 아니다.
            throw NotFoundException.of("Stop", command.routeId() + "/" + command.stopSeq());
        }

        List<OrderScan> outcomes = new ArrayList<>(atStop.size());
        List<ShipmentEvent> appended = new ArrayList<>(atStop.size());
        int afterCancel = 0;

        for (Shipment shipment : atStop) {
            ScanOutcome outcome = shipment.recordScan(command.type(), command.occurredAt());
            switch (outcome) {
                case APPLIED -> {
                    shipments.update(shipment);
                    appended.add(eventOf(command, shipment));
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

        events.appendAll(appended);
        metrics.countScanAfterCancel(afterCancel);

        // 사유도 좌표도 남기지 않는다 (§9.3 — 고객 식별 정보 금지).
        log.debug("스캔을 적용했다. routeId={}, stopSeq={}, type={}, applied={}, stale={}, afterCancel={}",
                command.routeId(), command.stopSeq(), command.type(), appended.size(),
                outcomes.size() - appended.size() - afterCancel, afterCancel);

        return new ScanResult(command.routeId(), command.stopSeq(), command.type(),
                command.occurredAt(), outcomes);
    }

    private ShipmentEvent eventOf(ScanCommand command, Shipment shipment) {
        return new ShipmentEvent(ids.newUuid(), shipment.orderId(), shipment.routeId(),
                command.type(), command.occurredAt(), command.lat(), command.lng(),
                command.failureReason());
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
