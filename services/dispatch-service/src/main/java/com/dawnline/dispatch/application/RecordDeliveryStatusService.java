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
 * <h2>계획은 {@code (route, revision, seq)} 로, 사실은 {@code orderId} 로 식별한다</h2>
 * ADR-047 결정 1 이다. 계약은 {@code routeId} 와 {@code stopSeq} 를 싣지만 <strong>조회에 쓰지
 * 않는다</strong> — 둘 다 <em>개정본</em>의 좌표라 §6.8 의 {@code relocate} 와 §6.10 의 취소가
 * 그 뜻을 바꾼다. 주문 id 는 개정을 가로질러 같은 것을 가리킨다 — tracking 이
 * {@code shipments.order_id} 를 PK 로 두는 것, 현실의 기사가 stop 번호가 아니라 송장을 찍는
 * 것과 같은 축이다.
 *
 * <h2>라우트로 좁히지 않는다</h2>
 * {@code routeId} 는 <strong>확인용 컨텍스트</strong>다. 「이 라우트에 없으면 철 지난 것」으로
 * 두면, 재계획이 주문을 B 로 옮기는 동안 A 의 기사가 끝낸 배송이 전부 버려진다 — 그러면
 * dispatch 는 그 주문이 B 에서 <em>미완료</em>라고 믿고 B 의 기사를 이미 배송된 곳으로 보낸다.
 * 다른 라우트에서 찾으면 거기 적용하고 {@code dawnline_status_after_relocate_total} 로 센다.
 * 어느 라우트에도 없을 때만 철 지난 것이다 (ADR-047 결정 2).
 *
 * <h2>개정 번호로 거르지 않는다</h2>
 * {@code route.assigned} 는 <em>계획</em>이라 옛 것을 버려야 하지만 이 이벤트는 <em>사실</em>이다.
 * 「현재 revision 보다 낮으면 버린다」를 여기에 옮기면 재계획 직전에 완료된 stop 들이 전부
 * 사라지고, 그것이 §6.8 이 읽는 바로 그 값이다 (ADR-047 결정 3).
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
            // 어느 라우트에도 그 주문들의 stop 이 없다 — 개정이 지운 자리의 뒤늦은 스캔이거나,
            // 라우트가 정리된 뒤의 replay 다. 사실은 여전히 참이지만 그 사실이 살 곳은 orders 와
            // shipments 이지 계획 테이블이 아니다 (ADR-047 기각 (3)).
            log.debug("어느 라우트에도 없는 stop 의 상태다. routeId={}, stopSeq={}, status={}",
                    command.routeId(), command.stopSeq(), command.status());
            metrics.deliveryStatusStale(1);
            return;
        }

        RouteMutations.AssignedStop stop = located.get();
        boolean relocated = !stop.routeId().equals(command.routeId());
        if (relocated) {
            // 신호는 카운터다. 여기서 info 로 올리면 재계획 한 번에 수십 줄이 나온다.
            log.debug("재배치 뒤에 도착한 사실이다. 이벤트 routeId={}, 지금 routeId={}, status={}",
                    command.routeId(), stop.routeId(), command.status());
        } else if (stop.seq() != command.stopSeq()) {
            // 판정에는 쓰지 않는다 — 쓰는 순간 결정 1 이 무너진다. 남기는 이유는 개정이 언제
            // 끼어들었는지를 로그만으로 잴 수 있게 하기 위해서다.
            log.debug("개정이 순번을 옮겼다. routeId={}, 페이로드 seq={}, 저장된 seq={}",
                    command.routeId(), command.stopSeq(), stop.seq());
        }

        switch (RouteStopTransition.decide(stop.status(), command.status())) {
            case Verdict.APPLY -> apply(command, stop);
            case Verdict.STALE -> {
                log.debug("철 지난 상태다. routeId={}, stopSeq={}, 현재={}, 보고={}",
                        stop.routeId(), stop.seq(), stop.status(), command.status());
                metrics.deliveryStatusStale(1);
            }
            case Verdict.AFTER_CANCEL -> {
                log.warn("계획에서 뺀 지점에 배송이 일어났다. routeId={}, stopSeq={}, 보고={}",
                        stop.routeId(), stop.seq(), command.status());
                metrics.scanAfterCancel(1);
            }
        }

        // 맨 뒤다 — 어느 분기로 갔든 적재 뒤에 센다. 「카운터는 커밋 뒤에」의 자리이고,
        // 그 순서를 테스트가 본다(적재가 실패하면 올리지 않는다).
        if (relocated) {
            metrics.statusAfterRelocate(1);
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
        // occurredAt 을 함께 적는다 — 계약은 그 값을 이미 싣고 있었고 버리고 있었다.
        // 이 한 칸이 §6.8 의 편차를 dispatch 안에 만든다 (ADR-048 결정 1): 없으면 재계획이
        // 편차를 at-risk 페이로드에서 읽게 되고, 그 순간 「진실 하나」가 소속은 dispatch ·
        // 시각은 tracking 으로 갈린다. 처음 닿은 시각만 남는 것은 어댑터가 지킨다.
        routes.markStopStatus(stop.stopId(), command.status(), command.occurredAt());
        // 이벤트가 말한 라우트가 아니라 stop 이 «지금 있는» 라우트의 진행을 다시 만든다.
        // 재배치된 건에서 둘은 다르고, 값이 틀리는 쪽은 언제나 이벤트 쪽이다.
        routes.progressOf(stop.routeId())
                .ifPresent(value -> progress.put(stop.routeId(), value));

        log.debug("stop 상태를 옮겼다. routeId={}, stopSeq={}, status={}, occurredAt={}",
                stop.routeId(), stop.seq(), command.status(), command.occurredAt());
    }

    /**
     * 그 주문들이 <strong>지금</strong> 있는 stop 을 찾는다 — 라우트로 좁히지 않는다.
     *
     * <p>주문을 <strong>하나씩</strong> 보는 이유: 운영자 재배정({@code moveOrder})은 stop 이
     * 아니라 주문 하나를 옮기므로 한 stop 의 주문들이 갈라진 채로 도착할 수 있다. 갈라졌으면
     * <strong>이벤트의 라우트에 있는 쪽</strong>을 택한다 — 기사가 실제로 서 있던 자리이고,
     * 그쪽을 고르는 것이 둘 중 보수적인 선택이다(덜 표시하면 기사가 한 번 더 가고, 더 표시하면
     * 배송되지 않은 주문이 계획에서 사라진다). 보통은 첫 조회에서 끝난다.
     */
    private Optional<RouteMutations.AssignedStop> locate(DeliveryStatusCommand command) {
        RouteMutations.AssignedStop elsewhere = null;
        for (UUID orderId : command.orderIds()) {
            Optional<RouteMutations.AssignedStop> found = routes.findAssignedStop(orderId);
            if (found.isEmpty()) {
                continue;
            }
            if (found.get().routeId().equals(command.routeId())) {
                return found;
            }
            if (elsewhere == null) {
                elsewhere = found.get();
            }
        }
        return Optional.ofNullable(elsewhere);
    }
}
