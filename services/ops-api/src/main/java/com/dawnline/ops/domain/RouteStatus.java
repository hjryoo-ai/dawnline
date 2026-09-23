package com.dawnline.ops.domain;

/**
 * {@code rm_routes.status} (DESIGN.md §5.5). 선언 순서가 진행 순서다.
 *
 * <p>{@code DEPARTED} 를 쓰는 토픽이 둘이다 — {@code delivery.route-departed} 와
 * {@code delivery.status}. 배송이 일어났다면 라우트는 출발한 것이고(ADR-017 의 「건너뜀은 사실」),
 * 기사가 {@code DEPARTED_CAMP} 스캔을 빼먹어도 화면이 「출발 안 함」에 머물면 안 된다. 그때
 * {@code departed_at} 은 비어 있다 — 출발했다는 것은 알지만 언제인지는 모른다.
 */
public enum RouteStatus {

    /** {@code route.assigned}. */
    ASSIGNED,

    /** {@code delivery.route-departed} 또는 그 라우트의 {@code delivery.status}. */
    DEPARTED
}
