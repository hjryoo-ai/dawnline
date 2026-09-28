package com.dawnline.dispatch.application.port.out;

import com.dawnline.common.TimeWindow;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 발행 직전 재검증이 차량의 활성을 다시 본다 ([ADR-067 후속](docs/adr/ADR-067-peak-fleet-is-an-operator-command.md) — 7-0 A33).
 *
 * <p>계획은 시작할 때 차량을 읽고 계산은 트랜잭션 밖이다(ADR-064). 그 사이의 비활성화는 409 가 막지 못한다 — 그 차량에는 아직
 * stop 이 없다. 그래서 발행하는 트랜잭션이 라우트의 차량을 다시 읽는다.
 *
 * <p>같은 자리에서 점유도 다시 읽는다([ADR-075](docs/adr/ADR-075-promise-start-is-a-floor-vehicle-time-belongs-to-the-earlier-plan.md)
 * 결정 4). 운영자 재실행은 {@code wave.closed} 의 파티션을 지나지 않아, 같은 캠프의 두 계획이 동시에 「차가 비어 있다」를 읽을 수 있다.
 */
public interface VehicleActivity {

    /**
     * 차량들을 <strong>{@code FOR NO KEY UPDATE}</strong> 로 다시 읽고 비활성인 것을 돌려준다.
     *
     * <p>잠금이 요점이다 — 비활성화의 {@code FOR UPDATE} 와 직렬화하고, <strong>발행끼리도</strong> 직렬화한다(ADR-075 결정 4 —
     * 처음 판의 {@code FOR SHARE} 는 발행끼리 막지 않았다). 라우트 INSERT 의 FK 검사({@code FOR KEY SHARE})와는 충돌하지 않는다. 발행이 먼저 잠그면 비활성화는 커밋을 기다렸다가 끝나지 않은
     * stop 을 보고 409 이고, 비활성화가 먼저면 이 읽기가 기다렸다가 최신 행을 본다. 활성은 엔티티가 아니라 칸으로 읽는다 — 영속성
     * 컨텍스트의 사본은 잠근 순간의 행을 덮지 않는다(CLAUDE.md).
     *
     * @param vehicleIds 발행하려는 라우트의 차량들
     * @return 그중 비활성인 것. 행이 없는 id 도 여기에 든다 — 없는 차량의 라우트는 발행할 수 없다
     */
    Set<UUID> lockInactive(Collection<UUID> vehicleIds);

    /**
     * 차량들의 점유 — 다른 웨이브의 끝나지 않은 라우트의 계획 시각 {@code [출발, 출발 + 소요)}.
     *
     * <p>{@link #lockInactive} 로 차량을 잠근 <strong>뒤에</strong> 부른다. 그래야 같은 차를 잠그려던 다른 발행이 이미 커밋했다면 그
     * 라우트가 여기 보이고, 아직이라면 그 발행이 이 커밋을 기다렸다가 이쪽 라우트를 본다.
     *
     * @param vehicleIds 발행하려는 라우트의 차량들
     * @param waveId     이 웨이브 — 자기 라우트는 세지 않는다
     * @return 차량별 점유. 점유가 없는 차량은 맵에 없다
     */
    Map<UUID, List<TimeWindow>> occupied(Collection<UUID> vehicleIds, UUID waveId);
}
