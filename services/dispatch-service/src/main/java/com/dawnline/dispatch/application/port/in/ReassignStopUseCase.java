package com.dawnline.dispatch.application.port.in;

import java.util.UUID;

/**
 * 운영자가 stop 을 다른 라우트로 옮긴다 (DESIGN.md §5.3
 * {@code POST /api/v1/routes/{routeId}/stops/{orderId}/reassign}).
 *
 * <h2>재계획이 아니다</h2>
 * §6.8 의 부분 재계획은 <em>알고리즘이</em> 다시 푸는 것이고, 이것은 <strong>사람이 하나를
 * 옮기는 것</strong>이다. 그래서 남은 경로를 다시 풀지 않고 시간만 재전파한다 — ADR-026 이
 * 취소에 대해 정한 것과 같은 원칙이다. 다시 풀 가치가 있으면 그 판단은 at-risk 가 한다.
 */
public interface ReassignStopUseCase {

    /**
     * @param routeId       현재 라우트
     * @param orderId       옮길 주문
     * @param targetRouteId 옮겨 갈 라우트. 같은 계획이어야 한다
     * @return 두 라우트의 새 개정 번호
     */
    Result reassign(UUID routeId, UUID orderId, UUID targetRouteId);

    /**
     * 결과.
     *
     * @param orderId        옮긴 주문
     * @param fromRouteId    떠난 라우트
     * @param fromRevision   그 라우트의 새 개정 번호
     * @param toRouteId      도착한 라우트
     * @param toRevision     그 라우트의 새 개정 번호
     */
    record Result(UUID orderId, UUID fromRouteId, int fromRevision, UUID toRouteId,
            int toRevision) {
    }
}
