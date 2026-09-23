package com.dawnline.tracking.application.port.out;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code route_revisions} — 라우트당 마지막으로 적용한 개정
 * ([ADR-045](docs/adr/ADR-045-revision-comparison-is-per-route.md), DESIGN.md §8.5).
 *
 * <p>메서드가 하나뿐인 것은 비교와 기록이 <strong>나뉠 수 없기 때문</strong>이다. 「읽고 → 비교하고
 * → 쓴다」로 적으면 그 사이가 창이 되고, 같은 라우트의 두 개정이 동시에 들어오면 둘 다 자기가
 * 최신이라고 읽는다. 한 문장(`ON CONFLICT … DO UPDATE … WHERE`)이면 그 창이 없다.
 */
public interface RouteRevisions {

    /**
     * 이 개정을 선점한다 — 저장된 개정이 없거나 <em>더 낮을 때만</em> 성공한다.
     *
     * <p>같은 번호도 실패다. 계약이 「자신이 이미 본 revision 보다 낮거나 같은 이벤트를 무시」로
     * 적혀 있고({@code route.assigned.v1} 의 {@code revision}), 같은 번호의 재발행은
     * 새 정보를 담지 않는다.
     *
     * <p>{@code campId} 를 함께 쓴다 — 캠프는 라우트의 성질이고, 이 표가 라우트당 한 행이라
     * 그 값이 사는 자리다(§9.1 {@code dawnline_at_risk_total\u007bcamp\u007d}). 갱신될 때도 같이
     * 덮는다: 라우트의 캠프가 바뀌는 일은 없지만, 「선점한 개정이 말하는 캠프」와 저장된 값이
     * 갈라질 자리를 남기지 않는 편이 낫다.
     *
     * @param routeId   라우트 id
     * @param revision  이 이벤트의 개정 번호 (1 이상)
     * @param campId    이 라우트의 캠프 (계약에서 {@code required})
     * @param plannedDeparture 계획 출발 시각 (계약에서 {@code required}). 개정마다 갱신된다 —
     *                  재계획은 출발 시각도 다시 정한다
     * @param appliedAt 적용 시각 — 주입된 시계에서 온 값이다 (불변규칙 12)
     * @return 선점했으면 {@code true}. {@code false} 면 이 이벤트는 지난 개정이다
     */
    boolean claim(UUID routeId, int revision, UUID campId, Instant plannedDeparture,
            Instant appliedAt);

    /**
     * 라우트의 계획값 — 스캔 경로가 읽는다.
     *
     * <p>{@code DEPARTED_CAMP} 의 편차 기준({@code plannedDeparture})과 메트릭의 {@code camp}
     * 라벨이 여기서 온다. 두 값을 <strong>한 번에</strong> 돌려주는 이유는 스캔마다 같은 행을
     * 두 번 읽지 않기 위해서다.
     *
     * @param routeId 라우트 id
     * @return 그 라우트의 계획값. {@code route.assigned} 를 아직 받지 못했으면 빈 값
     */
    Optional<RoutePlanned> find(UUID routeId);

    /**
     * 라우트당 한 행이 말하는 것.
     *
     * @param campId           캠프 (§9.1 {@code camp} 라벨)
     * @param revision         지금 적용해 둔 개정 — 출발이 「어느 개정본의 계획에 대해」 늦었는지를
     *                         말한다({@code delivery.route-departed}, ADR-050 결정 3)
     * @param plannedDeparture 계획 출발 시각 (§5.4 {@code DEPARTED_CAMP} 편차 기준)
     */
    record RoutePlanned(UUID campId, int revision, Instant plannedDeparture) {

        public RoutePlanned {
            Objects.requireNonNull(campId, "campId");
            Objects.requireNonNull(plannedDeparture, "plannedDeparture");
        }
    }
}
