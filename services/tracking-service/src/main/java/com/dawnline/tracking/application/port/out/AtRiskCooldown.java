package com.dawnline.tracking.application.port.out;

import java.util.UUID;

/**
 * 라우트당 at-risk 알림 쿨다운 (DESIGN.md §5.4 · §7.2 — {@code route:{id}:atrisk:cooldown}).
 *
 * <h2>이 쿨다운이 지키는 것은 알림 수이지 정확성이 아니다</h2>
 * 한 라우트가 늦어지는 동안 스캔은 계속 들어오고, 그때마다 발행하면 같은 사실이 stop 수만큼
 * 반복된다. 그것을 줄이는 것이 여기서 하는 일의 전부다. <strong>Redis 가 죽으면 중복 발행되고,
 * 그것은 §7.2 가 「허용」으로 정해 둔 폴백이다</strong> — 알림이 시끄러워지는 것과 위험을 아예
 * 놓치는 것 중 후자가 훨씬 나쁘다.
 *
 * <p>그러니까 <em>재계획이 두 번 도는 것</em>을 막는 장치가 아니다. 그쪽은 dispatch 가 DB 로
 * 지킨다(§6.8 의 10분 쿨다운, {@code routes.last_replanned_at} — 재계획 트랜잭션 안에서 비교하고
 * 갱신한다). 멱등 소비자({@code processed_events})로는 막지 못한다: 두 at-risk 는 서로 다른
 * {@code eventId} 라 둘 다 「처음 보는 이벤트」다. 두 쿨다운의 집이 다른 이유가 이것이고,
 * [ADR-046](docs/adr/ADR-046-at-risk-is-an-event.md) 이 그 경계를 적어 둔다.
 */
public interface AtRiskCooldown {

    /**
     * 이 라우트에 지금 알려도 되는가 — 되면 창을 연다 ({@code SET NX PX}).
     *
     * @param routeId 라우트 id
     * @return 발행해도 되면 {@code true}. 창이 열려 있으면 {@code false}
     */
    boolean tryStart(UUID routeId);
}
