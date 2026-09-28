package com.dawnline.dispatch.application;

import com.dawnline.dispatch.application.port.in.RecordDeliveryStatusUseCase;
import com.dawnline.dispatch.application.port.out.RouteMutations;
import com.dawnline.dispatch.domain.RouteStopTransition;
import com.dawnline.dispatch.domain.RouteStopTransition.Verdict;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code delivery.status} 를 <strong>주문의 행</strong>에 적고 stop 의 상태를 그 주문들에서 다시 센다 (DESIGN.md §4.1·§6.10, ADR-047 · ADR-071).
 *
 * <h2>이것이 무엇을 여는가</h2>
 * 둘이다 — §6.8 부분 재계획의 「미완료 stop 만」, 그리고 §6.10 넷째 분기
 * ({@code dawnline_cancel_too_late_total}). 둘째는 <strong>코드가 더해지지 않는다</strong>:
 * {@link CancelOrderService} 의 거부는 Phase 3 부터 있었고 없던 것은
 * {@code route_stops.status} 가 {@code ARRIVED} 에 닿는 경로였다.
 *
 * <p>셋이라고 적혀 있었다 (2026-09-23, Phase 6-0c 정정). 셋째는 §7.2 의
 * {@code route:{id}:progress} 를 채우는 것이었는데 <strong>그 키는 지웠다</strong> — 읽는 쪽이
 * 끝내 나타나지 않았고, {@code GET /routes/{routeId}} 가 stop 마다 살아 있는 상태를 이미
 * 돌려주므로 캐시를 읽는 것은 같은 사실의 둘째 출처를 만드는 일이었다
 * (ADR-048 결정 1 과 같은 근거).
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
 *
 * <h2>사실은 주문의 행에 — stop 은 그 주문들에서 다시 센다</h2>
 * 처음에는 찾은 stop 하나의 칸({@code route_stops.status})에 적었다 — 그 stop 의 어느 주문의 사건이 와도 stop 전체가 그 상태가 됐다. 한
 * stop 에 주문이 여럿이면 하나가 나머지의 사실을 대신 말했고, 배송되지 않은 주문이 계획에서 끝난 것이 됐다(ADR-071, 근거: 관측(재현됨)).
 * 지금은 사건의 주문마다 <em>그 주문이 지금 있는</em> 행에 전이를 판정해 적고, 건드린 stop 마다 다시 센다. 한 사건의 주문들이 서로 다른
 * stop 에 있으면 각자의 자리에 적힌다 — 「하나를 택한다」(ADR-047 결정 2 의 마지막 문단)는 stop 하나의 칸이 있을 때의 타협이었다.
 *
 * <h2>카운터는 사건 단위다</h2>
 * 주문 단위로 세면 같은 이름의 값이 뜻을 바꾼다(§9.1). 적용된 주문이 하나도 없으면 철 지난 사건 하나, 취소된 주문이 하나라도 있으면 취소 뒤
 * 도착 하나, 적용한 주문 중 하나라도 다른 라우트에 있으면 재배치 뒤 도착 하나다(ADR-071 결정 4).
 */
public class RecordDeliveryStatusService implements RecordDeliveryStatusUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordDeliveryStatusService.class);

    private final RouteMutations routes;
    private final DispatchMetrics metrics;

    /**
     * @param routes  라우트 조작
     * @param metrics §9.1 메트릭
     */
    public RecordDeliveryStatusService(RouteMutations routes, DispatchMetrics metrics) {
        this.routes = Objects.requireNonNull(routes, "routes");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    @Transactional
    public void record(DeliveryStatusCommand command) {
        Objects.requireNonNull(command, "command");

        List<RouteMutations.OrderAtStop> found = new ArrayList<>(command.orderIds().size());
        for (UUID orderId : command.orderIds()) {
            routes.findOrderAtStop(orderId).ifPresent(found::add);
        }
        if (found.isEmpty()) {
            // 어느 라우트에도 그 주문들의 stop 이 없다 — 개정이 지운 자리의 뒤늦은 스캔이거나,
            // 라우트가 정리된 뒤의 replay 다. 사실은 여전히 참이지만 그 사실이 살 곳은 orders 와
            // shipments 이지 계획 테이블이 아니다 (ADR-047 기각 (3)).
            log.debug("어느 라우트에도 없는 주문들의 상태다. routeId={}, stopSeq={}, status={}",
                    command.routeId(), command.stopSeq(), command.status());
            metrics.deliveryStatusStale(1);
            return;
        }

        Set<UUID> touched = new LinkedHashSet<>();
        boolean relocated = false;
        boolean afterCancel = false;
        for (RouteMutations.OrderAtStop order : found) {
            switch (RouteStopTransition.decide(order.status(), command.status())) {
                case Verdict.APPLY -> {
                    // occurredAt 을 함께 적는다 — 이 칸이 §6.8 의 편차를 dispatch 안에 만든다(ADR-048 결정 1). 처음 닿은 시각만 남는 것은
                    // 어댑터가 지킨다.
                    routes.markOrderStatus(order.stopId(), order.orderId(), command.status(), command.occurredAt());
                    touched.add(order.stopId());
                    relocated |= !order.routeId().equals(command.routeId());
                }
                case Verdict.STALE -> log.debug("철 지난 상태다. orderId={}, 현재={}, 보고={}",
                        order.orderId(), order.status(), command.status());
                case Verdict.AFTER_CANCEL -> {
                    log.warn("계획에서 뺀 주문에 배송이 일어났다. routeId={}, orderId={}, 보고={}",
                            order.routeId(), order.orderId(), command.status());
                    afterCancel = true;
                }
            }
        }
        // stop 은 그 주문들에서 다시 센다(ADR-071 결정 2) — 건드린 stop 마다 한 번.
        touched.forEach(routes::recountStop);
        if (relocated) {
            // 신호는 카운터다. 여기서 info 로 올리면 재계획 한 번에 수십 줄이 나온다.
            log.debug("재배치 뒤에 도착한 사실이다. 이벤트 routeId={}, status={}", command.routeId(), command.status());
        }

        // 맨 뒤다 — 어느 분기로 갔든 적재 뒤에 센다. 「카운터는 커밋 뒤에」의 자리이고,
        // 그 순서를 테스트가 본다(적재가 실패하면 올리지 않는다).
        if (afterCancel) {
            metrics.scanAfterCancel(1);
        } else if (touched.isEmpty()) {
            metrics.deliveryStatusStale(1);
        }
        if (relocated) {
            metrics.statusAfterRelocate(1);
        }
    }
}
