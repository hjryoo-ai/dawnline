package com.dawnline.dispatch.application.port.out;

import com.dawnline.dispatch.domain.optimizer.Stop;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    void rewrite(UUID routeId, com.dawnline.dispatch.domain.optimizer.PlannedRoute route);

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
     * 라우트의 머리 정보.
     *
     * @param routeId   라우트 id
     * @param planId    소속 계획
     * @param vehicleId 차량
     */
    record RouteHeader(UUID routeId, UUID planId, UUID vehicleId) {
    }
}
