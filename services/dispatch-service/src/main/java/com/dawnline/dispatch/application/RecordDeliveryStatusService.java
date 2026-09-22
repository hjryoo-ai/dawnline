package com.dawnline.dispatch.application;

import com.dawnline.dispatch.application.port.in.RecordDeliveryStatusUseCase;
import com.dawnline.dispatch.application.port.out.RouteMutations;
import com.dawnline.dispatch.application.port.out.RouteProgressCache;
import com.dawnline.dispatch.domain.RouteStopTransition;
import com.dawnline.dispatch.domain.RouteStopTransition.Verdict;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code delivery.status} 를 {@code route_stops.status} 로 옮긴다 (DESIGN.md §4.1·§6.10, ADR-047).
 *
 * <h2>이것이 무엇을 여는가</h2>
 * 셋이다 — §6.8 부분 재계획의 「미완료 stop 만」, §7.2 {@code route:{id}:progress} 의 dispatch 쪽
 * 입력, 그리고 §6.10 넷째 분기({@code dawnline_cancel_too_late_total}). 셋째는 <strong>코드가
 * 더해지지 않는다</strong>: {@link CancelOrderService} 의 거부는 Phase 3 부터 있었고 없던 것은
 * {@code route_stops.status} 가 {@code ARRIVED} 에 닿는 경로였다.
 *
 * <h2>주문으로 찾는다</h2>
 * 계약은 {@code stopSeq} 를 싣지만 <strong>조회에 쓰지 않는다</strong>. {@code seq} 는
 * <em>개정본</em>의 좌표라 §6.8 의 {@code relocate} 와 §6.10 의 취소가 그 뜻을 바꾼다. 주문 id 는
 * 개정을 가로질러 같은 것을 가리킨다 — tracking 이 {@code shipments.order_id} 를 PK 로 두는 것,
 * 기사 시뮬레이터가 「끝낸 stop」을 주문으로 기억하는 것과 같은 축이다 (ADR-047 결정 1).
 *
 * <h2>개정 번호로 거르지 않는다</h2>
 * {@code route.assigned} 는 <em>계획</em>이라 옛 것을 버려야 하지만 이 이벤트는 <em>사실</em>이다.
 * 「현재 revision 보다 낮으면 버린다」를 여기에 옮기면 재계획 직전에 완료된 stop 들이 전부
 * 사라지고, 그것이 §6.8 이 읽는 바로 그 값이다 (ADR-047 결정 2).
 */
public class RecordDeliveryStatusService implements RecordDeliveryStatusUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordDeliveryStatusService.class);

    private final RouteMutations routes;
    private final RouteProgressCache progress;
    private final DispatchMetrics metrics;

    /**
     * @param routes   라우트 조작
     * @param progress 진행 캐시 (§7.2)
     * @param metrics  §9.1 메트릭
     */
    public RecordDeliveryStatusService(RouteMutations routes, RouteProgressCache progress,
            DispatchMetrics metrics) {

        this.routes = Objects.requireNonNull(routes, "routes");
        this.progress = Objects.requireNonNull(progress, "progress");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    @Transactional
    public void record(DeliveryStatusCommand command) {
        Objects.requireNonNull(command, "command");

        Optional<RouteMutations.AssignedStop> located = locate(command);
        if (located.isEmpty()) {
            // 이 라우트에 그 주문들의 stop 이 없다 — relocate 가 옮겼거나 개정이 지운 자리의
            // 뒤늦은 스캔이다. 사실은 여전히 참이지만 그 사실이 살 곳은 orders 와 shipments
            // 이지 계획 테이블이 아니다 (ADR-047 기각 (3)).
            log.debug("이 라우트에 없는 stop 의 상태다. routeId={}, stopSeq={}, status={}",
                    command.routeId(), command.stopSeq(), command.status());
            metrics.deliveryStatusStale(1);
            return;
        }

        RouteMutations.AssignedStop stop = located.get();
        if (stop.seq() != command.stopSeq()) {
            // 판정에는 쓰지 않는다 — 쓰는 순간 결정 1 이 무너진다. 남기는 이유는 개정이 언제
            // 끼어들었는지를 로그만으로 잴 수 있게 하기 위해서다.
            log.debug("개정이 순번을 옮겼다. routeId={}, 페이로드 seq={}, 저장된 seq={}",
                    command.routeId(), command.stopSeq(), stop.seq());
        }

        switch (RouteStopTransition.decide(stop.status(), command.status())) {
            case Verdict.APPLY -> apply(command, stop);
            case Verdict.STALE -> {
                log.debug("철 지난 상태다. routeId={}, stopSeq={}, 현재={}, 보고={}",
                        command.routeId(), command.stopSeq(), stop.status(), command.status());
                metrics.deliveryStatusStale(1);
            }
            case Verdict.AFTER_CANCEL -> {
                log.warn("계획에서 뺀 지점에 배송이 일어났다. routeId={}, stopSeq={}, 보고={}",
                        command.routeId(), command.stopSeq(), command.status());
                metrics.scanAfterCancel(1);
            }
        }
    }

    /**
     * 상태를 옮기고 <strong>그 뒤에</strong> 진행 캐시를 다시 쓴다.
     *
     * <p>캐시는 증분이 아니라 DB 에서 다시 만든 값으로 덮는다. 증분으로 올리면 캐시가 「스캔을
     * 몇 번 받았는가」를 세게 되고, 그 수는 놓친 이벤트 하나로 영영 어긋난다 — 그리고 어긋난
     * 캐시는 §7.2 의 폴백이 <em>발동하지 않는</em> 경우라 아무도 고쳐 주지 않는다.
     */
    private void apply(DeliveryStatusCommand command, RouteMutations.AssignedStop stop) {
        routes.markStopStatus(stop.stopId(), command.status());
        routes.progressOf(command.routeId())
                .ifPresent(value -> progress.put(command.routeId(), value));

        log.debug("stop 상태를 옮겼다. routeId={}, stopSeq={}, status={}, occurredAt={}",
                command.routeId(), command.stopSeq(), command.status(), command.occurredAt());
    }

    /**
     * 이 라우트 안에서 그 주문들의 stop 을 찾는다.
     *
     * <p>주문을 <strong>하나씩</strong> 보는 이유: 운영자 재배정({@code moveOrder})은 stop 이
     * 아니라 주문 하나를 옮기므로 한 stop 의 주문들이 갈라진 채로 도착할 수 있다. 첫 주문
     * 하나로 판정하면 「옮겨 간 쪽」을 먼저 집었을 때 남아 있는 방문을 놓친다. 보통은 첫
     * 조회에서 끝난다.
     */
    private Optional<RouteMutations.AssignedStop> locate(DeliveryStatusCommand command) {
        for (UUID orderId : command.orderIds()) {
            Optional<RouteMutations.AssignedStop> found = routes.findAssignedStop(orderId);
            if (found.isPresent() && found.get().routeId().equals(command.routeId())) {
                return found;
            }
        }
        return Optional.empty();
    }
}
