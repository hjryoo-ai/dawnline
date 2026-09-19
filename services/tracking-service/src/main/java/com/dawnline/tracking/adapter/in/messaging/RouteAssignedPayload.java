package com.dawnline.tracking.adapter.in.messaging;

import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.AssignedStop;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.RouteAssignment;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code route.assigned} v1 중 <strong>tracking 이 읽는 필드만</strong>
 * ({@code contracts/events/route.assigned.v1.schema.json}).
 *
 * <p>계약의 {@code planId}·{@code waveId}·{@code campId}·{@code vehicleId}·{@code driverId}·
 * {@code strategy}·{@code summary} 와 stop 의 {@code lat}·{@code lng}·{@code serviceSeconds} 는
 * 여기 없다. 이 서비스가 쓰지 않기 때문이고, {@code EventJson} 이 모르는 필드를 무시하므로(§4.7)
 * 발행자가 필드를 더해도 깨지지 않는다 — 소비자가 자기가 읽는 것만 선언하는 것이 소비자 주도
 * 계약의 요점이다.
 *
 * <p><strong>운영 메모.</strong> {@code promisedWindow} 는 Phase 5-1a 에서 {@code required} 가
 * 됐다({@code contracts/events/README.md} §5 예외 표). 그 이전에 발행돼 개발 볼륨의 Kafka 에
 * 남아 있는 이벤트에는 이 필드가 없고, 그런 이벤트는 {@link #toAssignment()} 에서 멈춘다 —
 * 지어낸 창으로 채우면 at-risk 판정이 거짓 위에서 돌고 그 거짓은 아무 데서도 드러나지 않는다.
 * 해결은 그 토픽을 재생성하거나 컨슈머 그룹을 {@code latest} 로 옮기는 것이다.
 *
 * @param routeId  라우트 id
 * @param revision 개정 번호. 최초 확정이 1
 * @param stops    방문 순서대로의 stop 들
 */
public record RouteAssignedPayload(UUID routeId, int revision, List<StopPayload> stops) {

    /** {@code status} 의 취소 값 (ADR-026). */
    private static final String CANCELLED = "CANCELLED";

    /**
     * stop 하나.
     *
     * @param seq               방문 순번
     * @param orderIds          이 지점에서 배송할 주문들. 취소된 것도 남아 있다
     * @param cancelledOrderIds 그중 취소된 것들. 계약에서 {@code required} 가 아니라 널일 수 있고,
     *                          부재는 「취소 없음」이다 (스키마의 {@code default: []})
     * @param plannedArrival    계획 도착 시각
     * @param promisedWindow    약속창. {@code required} 지만 옛 이벤트에는 없다 (위 운영 메모)
     * @param status            {@code PLANNED} 또는 {@code CANCELLED}. 부재는 {@code PLANNED} 다
     */
    public record StopPayload(int seq, List<UUID> orderIds,
            @Nullable List<UUID> cancelledOrderIds, Instant plannedArrival,
            @Nullable Window promisedWindow, @Nullable String status) {
    }

    /**
     * 약속창.
     *
     * @param start 시작. tracking 은 읽지 않는다 — at-risk 는 끝만 본다 (§5.4)
     * @param end   끝
     */
    public record Window(@Nullable Instant start, Instant end) {
    }

    /**
     * 유스케이스의 명령으로 옮긴다.
     *
     * <p>계약의 두 가지 취소 표현({@code status: CANCELLED} 와 {@code cancelledOrderIds})을
     * <strong>여기서 하나로 접는다</strong>. 도메인에는 「이 주문이 취소됐는가」 하나만 있으면
     * 되고, 둘을 도메인까지 들고 가면 같은 질문에 답이 둘이 된다. 합집합을 쓰는 이유는 둘이
     * 어긋날 때 <em>더 많이 취소된 쪽</em>이 안전하기 때문이다 — 취소된 주문을 배송하는 것이
     * 그 반대보다 비싸다 (§6.10 {@code dawnline_cancel_too_late_total}).
     *
     * @return 명령
     * @throws IllegalStateException 약속창 없는 stop 이 있으면 (위 운영 메모)
     */
    public RouteAssignment toAssignment() {
        return new RouteAssignment(routeId, revision, stops.stream().map(this::stopOf).toList());
    }

    private AssignedStop stopOf(StopPayload stop) {
        if (stop.promisedWindow() == null || stop.promisedWindow().end() == null) {
            throw new IllegalStateException("""
                    약속창 없는 stop 은 배송으로 만들 수 없습니다: routeId=%s seq=%d. \
                    route.assigned.v1 의 promisedWindow 는 required 이므로(Phase 5-1a 계약), \
                    이 이벤트는 그 필드가 생기기 전에 발행된 것이다. \
                    토픽을 재생성하거나 컨슈머 그룹을 latest 로 옮긴다 \
                    (contracts/events/README.md §5)."""
                    .formatted(routeId, stop.seq()));
        }
        Set<UUID> cancelled = new LinkedHashSet<>();
        if (stop.cancelledOrderIds() != null) {
            cancelled.addAll(stop.cancelledOrderIds());
        }
        if (CANCELLED.equals(stop.status())) {
            cancelled.addAll(stop.orderIds());
        }
        return new AssignedStop(stop.seq(), stop.orderIds(), cancelled,
                stop.plannedArrival(), stop.promisedWindow().end());
    }
}
