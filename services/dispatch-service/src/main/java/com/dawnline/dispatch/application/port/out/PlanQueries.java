package com.dawnline.dispatch.application.port.out;

import com.dawnline.dispatch.application.port.in.PlanView;
import com.dawnline.dispatch.application.port.in.RouteView;
import java.util.Optional;
import java.util.UUID;

/**
 * 조회 전용 포트 (DESIGN.md §5.3 REST).
 *
 * <p>애그리거트를 거치지 않고 DTO 로 바로 뜬다. 운영자 화면은 <strong>읽기만</strong> 하고,
 * 5,000 stop 을 애그리거트로 되살리면 그것을 얻는 대가로 아무것도 얻지 못한다 — 같은 이유로
 * {@code JdbcPlannedRouteRepository} 도 엔티티를 두지 않았다.
 */
public interface PlanQueries {

    /**
     * @param planId 계획 id
     */
    Optional<PlanView> findPlan(UUID planId);

    /**
     * @param waveId 웨이브 id
     */
    Optional<PlanView> findPlanByWave(UUID waveId);

    /**
     * @param routeId 라우트 id
     */
    Optional<RouteView> findRoute(UUID routeId);
}
