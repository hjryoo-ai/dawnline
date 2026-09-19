package com.dawnline.tracking.application;

import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase;
import com.dawnline.tracking.application.port.out.RouteRevisions;
import com.dawnline.tracking.application.port.out.ShipmentRepository;
import com.dawnline.tracking.domain.Shipment;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * {@code route.assigned} 반영 (DESIGN.md §5.4, §8.5).
 *
 * <h2>순서가 규칙이다 — 개정을 먼저 선점한다</h2>
 * 배송을 먼저 고치고 개정을 나중에 기록하면, 그 사이에 죽었을 때 「고쳐졌는데 기록은 없는」 상태가
 * 남는다. 선점이 먼저면 실패는 트랜잭션과 함께 통째로 사라진다 — {@code IdempotentConsumer} 가
 * {@code processed_events} 를 먼저 선점하는 것과 같은 이유다(불변규칙 2).
 *
 * <h2>이 개정에 <strong>없는</strong> 배송은 건드리지 않는다</h2>
 * 이 클래스는 페이로드에 이름이 적힌 주문만 읽고 쓴다. 「이 라우트의 shipment 중 개정에 없는
 * 것을 정리한다」는 한 줄이 들어오면 <em>라우트 간 이동이 취소된다</em>: §6.8 의
 * {@code relocate} 가 주문을 A → B 로 옮기면 dispatch 는 A 의 개정(그 주문이 빠진)과 B 의
 * 개정(그 주문이 실린)을 <strong>서로 다른 파티션</strong>으로 발행하므로 둘 사이에 순서가 없다.
 * B 가 먼저 처리되면 A 의 개정에는 그 주문이 없고, 그때 「없으니 되돌린다」를 하면 방금 끝난
 * 이동이 지워진다. <strong>부재는 값이 아니다</strong>(ADR-026) — 여기서는 그것이 경합 방어선이다.
 * {@code RouteAssignmentIT.A_의_개정에_없는_shipment_는_건드리지_않는다} 가 이 문장을 지킨다.
 *
 * <h2>종결 상태는 그대로 둔다</h2>
 * {@code COMPLETED}·{@code FAILED}·{@code CANCELLED} 인 배송은 개정이 와도 갱신하지 않는다
 * ({@link Shipment#applyRevision}). Phase 5-5 전에는 dispatch 가 배송 진행을 모르므로 이 규칙이
 * tracking 쪽의 유일한 방어선이다 (§5.4).
 */
public class ApplyRouteAssignmentService implements ApplyRouteAssignmentUseCase {

    private final ShipmentRepository shipments;
    private final RouteRevisions revisions;
    private final Clock clock;

    /**
     * @param shipments {@code shipments} 저장소
     * @param revisions {@code route_revisions} 저장소 (ADR-045)
     * @param clock     {@code applied_at} 시각 출처 (불변규칙 12)
     */
    public ApplyRouteAssignmentService(ShipmentRepository shipments, RouteRevisions revisions,
            Clock clock) {
        this.shipments = Objects.requireNonNull(shipments, "shipments");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Outcome apply(RouteAssignment assignment) {
        Objects.requireNonNull(assignment, "assignment");

        if (!revisions.claim(assignment.routeId(), assignment.revision(), assignment.campId(),
                assignment.plannedDeparture(), clock.instant())) {
            return Outcome.stale();
        }

        Map<UUID, Shipment> existing = load(assignment.allOrderIds());
        Counts counts = new Counts();

        for (AssignedStop stop : assignment.stops()) {
            for (UUID orderId : stop.orderIds()) {
                applyStop(assignment.routeId(), stop, orderId, existing.get(orderId), counts);
            }
        }
        return new Outcome(Outcome.Kind.APPLIED,
                counts.created, counts.revised, counts.cancelled, counts.keptTerminal);
    }

    private void applyStop(UUID routeId, AssignedStop stop, UUID orderId,
            @Nullable Shipment existing, Counts counts) {

        if (existing == null) {
            // 이 라우트에서 처음 보는 주문이다. 개정 2가 개정 1보다 먼저 처리되면(재전달·DLQ
            // replay) 여기로 온다 — 그때 만들지 않으면 그 주문은 어떤 개정으로도 만들어지지
            // 않는다. 개정 1은 그 뒤에 와도 지난 개정이라 버려지기 때문이다.
            Shipment created = Shipment.scheduled(orderId, routeId, stop.seq(),
                    stop.plannedArrival(), stop.promisedEnd());
            if (stop.isCancelled(orderId) && created.cancel()) {
                counts.cancelled++;
            }
            shipments.insert(created);
            counts.created++;
            return;
        }

        if (!existing.applyRevision(routeId, stop.seq(), stop.plannedArrival(), stop.promisedEnd())) {
            // 종결 상태다. 취소도 얹지 않는다 — 배송이 끝난 주문에 취소가 왔다는 사실은
            // dispatch 의 dawnline_cancel_too_late_total 이 세는 몫이다 (§6.10 넷째 분기).
            counts.keptTerminal++;
            return;
        }
        if (stop.isCancelled(orderId) && existing.cancel()) {
            counts.cancelled++;
        }
        shipments.update(existing);
        counts.revised++;
    }

    private Map<UUID, Shipment> load(Set<UUID> orderIds) {
        List<Shipment> found = shipments.findAll(orderIds);
        return found.stream().collect(Collectors.toMap(Shipment::orderId, Function.identity(),
                (first, second) -> first, HashMap::new));
    }

    /** 로그 한 줄에 실을 네 수. */
    private static final class Counts {
        private int created;
        private int revised;
        private int cancelled;
        private int keptTerminal;
    }
}
