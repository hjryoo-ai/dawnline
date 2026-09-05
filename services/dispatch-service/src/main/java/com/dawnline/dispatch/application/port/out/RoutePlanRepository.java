package com.dawnline.dispatch.application.port.out;

import com.dawnline.dispatch.domain.RoutePlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 계획 저장소 (DESIGN.md §5.3 {@code route_plans}). */
public interface RoutePlanRepository {

    /**
     * 없으면 넣는다. {@code wave_id} 가 UNIQUE 라 <strong>웨이브당 계획은 하나</strong>다 —
     * {@code wave.closed} 중복 도착의 멱등이 여기서 만들어진다(§5.3).
     *
     * @param plan 계획
     * @return 실제로 넣었으면 참
     */
    boolean insertIfAbsent(RoutePlan plan);

    /**
     * @param waveId 웨이브 id
     */
    Optional<RoutePlan> findByWaveId(UUID waveId);

    /**
     * @param planId 계획 id
     */
    Optional<RoutePlan> findById(UUID planId);

    /**
     * 죽은 인스턴스가 남긴 계획들 (§5.3 — {@code PLANNING} 이고 {@code started_at} 이 지났다).
     *
     * @param startedBefore 이 시각 이전에 시작한 것
     * @param limit         한 번에 회수할 최대 개수
     */
    List<RoutePlan> findStalePlanning(Instant startedBefore, int limit);

    /**
     * @param plan 갱신할 계획
     */
    void update(RoutePlan plan);
}
