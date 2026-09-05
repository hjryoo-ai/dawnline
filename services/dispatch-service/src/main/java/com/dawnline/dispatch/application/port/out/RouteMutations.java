package com.dawnline.dispatch.application.port.out;

import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.Stop;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 라우트를 고치는 최소한의 연산 (DESIGN.md §5.3 운영자 재배정).
 *
 * <p>애그리거트를 되살리지 않는다. 라우트는 120 stop 까지 가고 여기서 바뀌는 것은
 * <strong>주문 하나의 소속</strong>뿐이다.
 */
public interface RouteMutations {

    /**
     * 라우트의 소속 계획과 차량.
     *
     * @param routeId 라우트 id
     */
    Optional<RouteHeader> findHeader(UUID routeId);

    /**
     * 라우트의 stop 들을 방문 순서대로 되살린다.
     *
     * <p>화물·약속창은 {@code route_stops} 에 없고 {@code dispatch_candidates} 에 있다 —
     * 계획의 근거는 후보이고 라우트는 그 결과이기 때문이다. 룰을 다시 돌리려면 근거가 필요하다.
     *
     * <p><strong>취소된 것은 빠진다</strong> — 취소된 stop 도, 살아 있는 stop 에 섞인 취소된
     * 주문도. 기사가 건너뛸 지점을 계산에 넣으면 그 지점까지의 이동 시간이 남은 stop 의 도착
     * 시각을 뒤로 민다 (§6.10). 취소된 것을 <em>발행</em>에서 지우지 않는 것과 계산에서 빼는
     * 것은 다른 일이다 — 발행은 {@link #snapshot} 이 만든다.
     *
     * @param routeId 라우트 id
     */
    List<Stop> loadStops(UUID routeId);

    /**
     * 이 주문이 실린 stop.
     *
     * @param routeId 라우트 id
     * @param orderId 주문 id
     */
    Optional<UUID> findStopOf(UUID routeId, UUID orderId);

    /**
     * 주문을 다른 라우트로 옮긴다. 목적지에 같은 지점의 stop 이 없으면 새로 만든다.
     *
     * @param fromStopId    떠나는 stop
     * @param orderId       주문
     * @param targetRouteId 도착 라우트
     */
    void moveOrder(UUID fromStopId, UUID orderId, UUID targetRouteId);

    /**
     * 이동 뒤 라우트를 다시 쓴다 — 순번 재부여, 시간 재전파, 요약 갱신.
     *
     * <p>시각과 비용은 <strong>도메인이 계산해서 넘긴다</strong>. 어댑터가 다시 계산하면 그
     * 계산이 두 곳이 되고, 두 곳은 갈라진다.
     *
     * @param routeId 라우트 id
     * @param route   다시 계산된 라우트. 비어 있으면 stop 을 전부 지운다
     */
    void rewrite(UUID routeId, PlannedRoute route);

    /**
     * 라우트를 비운다 (마지막 주문이 떠난 경우).
     *
     * @param routeId 라우트 id
     */
    void clear(UUID routeId);

    /**
     * 개정 번호를 올리고 새 값을 돌려준다 (§6.8 4단계).
     *
     * @param routeId 라우트 id
     */
    int bumpRevision(UUID routeId);

    /**
     * 이 주문이 실린 stop — 라우트를 모르는 채로 찾는다 (§6.10).
     *
     * <p>취소는 {@code orderId} 만 들고 온다. 그 주문이 이미 발행된 라우트에 실려 있는지가
     * 분기를 가르므로(ADR-026 결정 2) 여기서 한 번에 찾는다.
     *
     * @param orderId 주문 id
     */
    Optional<AssignedStop> findAssignedStop(UUID orderId);

    /**
     * 이 stop 의 주문이 <strong>전부</strong> 취소됐으면 stop 을 취소로 표시한다.
     *
     * <p>일부만 취소된 stop 은 여전히 방문한다 — 남은 주문을 배송해야 한다. 그래서 이 판정은
     * "stop 이 죽었는가" 이고 "주문이 죽었는가" 가 아니다
     * (ADR-026 [후속 정정 — Phase 3-6]).
     *
     * @param stopId stop id
     * @return 이 호출로 stop 이 취소됐으면 참
     */
    boolean cancelStopIfAllOrdersCancelled(UUID stopId);

    /**
     * 순서를 그대로 두고 시각만 다시 쓴다 (§6.10 — 재시퀀싱하지 않는다).
     *
     * <p>{@link #rewrite} 와 다른 점이 이것 하나다. 취소는 기사가 이미 보고 있는 순번을 바꾸지
     * 않으므로 {@code seq} 에 손대지 않고, 건너뛴 stop 만큼 뒤의 도착 시각이 앞으로 온다.
     *
     * @param routeId 라우트 id
     * @param route   살아 있는 stop 만으로 다시 계산한 라우트. 하나도 없으면 {@code null}
     */
    void retime(UUID routeId, @Nullable PlannedRoute route);

    /**
     * 저장된 그대로의 라우트 — <strong>취소된 stop 을 포함</strong>한다.
     *
     * <p>개정 발행의 입력이다. 계획 결과가 아니라 저장된 상태에서 만드는 이유는
     * {@link RouteSnapshot} 의 주석에 있다.
     *
     * @param routeId 라우트 id
     */
    Optional<RouteSnapshot> snapshot(UUID routeId);

    /**
     * 주문이 실린 stop 과 그 stop 의 상태.
     *
     * @param routeId 라우트 id
     * @param stopId  stop id
     * @param status  {@code route_stops.status} — {@code PLANNED|CANCELLED|ARRIVED|COMPLETED}
     */
    record AssignedStop(UUID routeId, UUID stopId, String status) {

        /** 기사가 이미 그 지점에 닿았는가. 닿았으면 취소는 거부된다 (ADR-026 결정 2 네 번째 행). */
        public boolean visited() {
            return "ARRIVED".equals(status) || "COMPLETED".equals(status);
        }
    }

    /**
     * 라우트의 머리 정보.
     *
     * @param routeId   라우트 id
     * @param planId    소속 계획
     * @param vehicleId 차량
     */
    record RouteHeader(UUID routeId, UUID planId, UUID vehicleId) {
    }
}
