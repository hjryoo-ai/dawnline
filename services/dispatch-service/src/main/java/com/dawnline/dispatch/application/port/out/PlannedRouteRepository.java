package com.dawnline.dispatch.application.port.out;

import com.dawnline.dispatch.domain.optimizer.Explanation;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 라우트·stop·설명 저장 (DESIGN.md §5.3). */
public interface PlannedRouteRepository {

    /**
     * 계획 결과를 저장하고 <strong>라우트 id 를 돌려준다</strong>.
     *
     * <p>id 는 여기서 생긴다 — 순수 함수는 아직 없는 식별자를 만들어 내지 않으므로
     * ({@link com.dawnline.dispatch.domain.optimizer.PlanResult} 에는 차량만 있다) 발행이
     * {@code route.assigned.routeId} 와 {@code order.dispatched.routeId} 를 쓰려면 이 매핑이
     * 필요하다.
     *
     * @param planId 계획 id
     * @param routes 확정된 라우트들 (입력 순서가 {@code seq_no} 가 된다)
     * @return 라우트별로 부여된 id. 입력과 같은 순서다
     */
    List<UUID> saveRoutes(UUID planId, List<PlannedRoute> routes);

    /**
     * 설명을 저장한다 (§6.3 — 운영자의 "왜" 에 답하는 유일한 기록).
     *
     * @param planId       계획 id
     * @param explanations 설명들
     * @param routeIds     차량 → 라우트 id. 배정 설명에 라우트를 붙이는 데 쓴다
     */
    void saveExplanations(UUID planId, List<Explanation> explanations,
            Map<com.dawnline.dispatch.domain.optimizer.VehicleId, UUID> routeIds);
}
