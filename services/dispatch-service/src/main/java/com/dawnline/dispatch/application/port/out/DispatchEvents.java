package com.dawnline.dispatch.application.port.out;

import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import java.util.List;
import java.util.UUID;

/**
 * 발행 (DESIGN.md §4.1, §5.3). Outbox 를 거친다 — {@code KafkaTemplate} 을 유스케이스에서
 * 직접 부르지 않는다(불변규칙 1).
 *
 * <p>세 이벤트는 <strong>같은 트랜잭션</strong>에 들어가야 한다(ADR-024). 나눠 넣으면
 * "완료라는데 라우트가 없다" 가 생긴다. 그래서 인터페이스가 세 메서드로 나뉘어 있어도
 * 호출부는 한 트랜잭션 안에서 셋을 모두 부른다.
 */
public interface DispatchEvents {

    /**
     * 라우트당 하나 (§4.1 키 {@code routeId}).
     *
     * @param plan     계획
     * @param routeId  저장하며 부여된 라우트 id
     * @param route    라우트
     * @param revision 개정 번호. 최초 확정이 1 (§6.8 4단계)
     */
    void routeAssigned(RoutePlan plan, UUID routeId, PlannedRoute route, int revision);

    /**
     * 주문당 하나 (§4.1 키 {@code orderId}).
     *
     * @param routeId  라우트 id
     * @param orderIds 그 라우트가 배송할 주문들
     */
    void ordersDispatched(UUID routeId, List<UUID> orderIds);

    /**
     * 웨이브당 하나 (§4.1 키 {@code waveId}, ADR-024).
     *
     * @param plan   계획
     * @param result 계획 결과
     */
    void planCompleted(RoutePlan plan, PlanResult result);

    /**
     * 계획 실패 (§5.3, ADR-024).
     *
     * @param plan 계획
     */
    void planFailed(RoutePlan plan);
}
