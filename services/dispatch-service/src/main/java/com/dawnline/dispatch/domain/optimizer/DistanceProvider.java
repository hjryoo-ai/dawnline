package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.GeoPoint;

/**
 * 두 지점 사이의 이동을 준다 (DESIGN.md §6.2).
 *
 * <p>기본 구현은 {@link HaversineDistance}(도로계수·평균 속도), 선택 구현은 OSRM 테이블 API +
 * 캐시다(§6.7). 거리 행렬은 <strong>stop 통합 후</strong>에 계산해 {@code O(n²)} 규모를 줄인다.
 *
 * <p>구현은 <strong>대칭</strong>이어야 한다 — {@code between(a, b)} 와 {@code between(b, a)} 가
 * 같아야 2-opt 같은 구간 뒤집기가 비용을 바꾸지 않는다. 일방통행이 있는 실제 도로망은 대칭이
 * 아니므로, OSRM 구현을 넣을 때 이 전제를 다시 봐야 한다.
 */
@FunctionalInterface
public interface DistanceProvider {

    /**
     * @param from 시작점
     * @param to   끝점
     */
    Travel between(GeoPoint from, GeoPoint to);
}
