package com.dawnline.dispatch.application.port.out;

import com.dawnline.dispatch.domain.RoutePlan;
import java.time.Duration;
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
     * 이 캠프의 <strong>마지막 발행 계획</strong>이 알고리즘에 쓴 시간 (§6.7 열화 판단의 둘째 조건).
     *
     * <p>캠프별인 이유는 계획 시간이 캠프 규모에 붙어 있어서다 — 큰 캠프의 느린 계획이 작은
     * 캠프를 열화시키면, 열화가 "이 캠프가 밀린다" 가 아니라 "어딘가 바쁘다" 를 뜻하게 된다.
     *
     * <p>DB 에서 읽는 이유는 인메모리 홀더가 <em>재기동에 사라지고 인스턴스마다 다르기</em>
     * 때문이다. 계획은 캠프당 하루 수십 건이라 조회 한 번은 예산(§6.7 30초)에서 비용이 아니다.
     *
     * @param campId 캠프 id
     * @return 발행된 계획이 없으면 빈 값 — 그때 이 조건은 {@code NONE} 이다
     */
    Optional<Duration> lastPublishedDuration(UUID campId);

    /**
     * @param plan 갱신할 계획
     */
    void update(RoutePlan plan);
}
