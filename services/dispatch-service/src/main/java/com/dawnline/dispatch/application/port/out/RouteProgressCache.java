package com.dawnline.dispatch.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code route:{id}:progress} 캐시 (DESIGN.md §7.2, TTL 2일).
 *
 * <p><strong>진실 저장소가 아니다</strong>(불변규칙 7). 구현은 Redis 가 없거나 죽었을 때
 * 예외를 밖으로 내지 않고 «없음» 으로 답하며, 그 사실을 조용히 넘기지 않고 센다 —
 * 폴백은 조용히 일어나면 안 된다.
 */
public interface RouteProgressCache {

    /**
     * 캐시에 적는다. 실패해도 호출자는 모른다 — 진실은 이미 DB 에 있다.
     *
     * @param routeId  라우트 id
     * @param progress 진행 상황
     */
    void put(UUID routeId, RouteProgress progress);

    /**
     * 캐시에서 읽는다.
     *
     * @param routeId 라우트 id
     * @return 없거나 읽지 못하면 빈 값 — <strong>둘을 구별하지 않는다.</strong> 호출자가 할 일이
     *         같기 때문이고(DB 로 간다), 구별이 필요한 곳은 지표다
     */
    Optional<RouteProgress> get(UUID routeId);
}
