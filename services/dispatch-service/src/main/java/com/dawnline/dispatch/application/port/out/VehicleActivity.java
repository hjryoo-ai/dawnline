package com.dawnline.dispatch.application.port.out;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * 발행 직전 재검증이 차량의 활성을 다시 본다 ([ADR-067 후속](docs/adr/ADR-067-peak-fleet-is-an-operator-command.md) — 7-0 A33).
 *
 * <p>계획은 시작할 때 차량을 읽고 계산은 트랜잭션 밖이다(ADR-064). 그 사이의 비활성화는 409 가 막지 못한다 — 그 차량에는 아직
 * stop 이 없다. 그래서 발행하는 트랜잭션이 라우트의 차량을 다시 읽는다.
 */
public interface VehicleActivity {

    /**
     * 차량들을 <strong>공유 잠금</strong>({@code FOR SHARE})으로 다시 읽고 비활성인 것을 돌려준다.
     *
     * <p>잠금이 요점이다 — 비활성화의 {@code FOR UPDATE} 와 직렬화한다. 발행이 먼저 잠그면 비활성화는 커밋을 기다렸다가 끝나지 않은
     * stop 을 보고 409 이고, 비활성화가 먼저면 이 읽기가 기다렸다가 최신 행을 본다. 활성은 엔티티가 아니라 칸으로 읽는다 — 영속성
     * 컨텍스트의 사본은 잠근 순간의 행을 덮지 않는다(CLAUDE.md).
     *
     * @param vehicleIds 발행하려는 라우트의 차량들
     * @return 그중 비활성인 것. 행이 없는 id 도 여기에 든다 — 없는 차량의 라우트는 발행할 수 없다
     */
    Set<UUID> lockInactive(Collection<UUID> vehicleIds);
}
