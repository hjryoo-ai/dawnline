package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.GeoPoint;
import java.util.Objects;
import java.util.UUID;

/**
 * 라우트의 출발·복귀 지점 (DESIGN.md §6.2).
 *
 * <p>모든 라우트는 캠프에서 나가 캠프로 돌아온다 — 거리·시간·근무창 판정이 전부 그 전제 위에 있다.
 *
 * @param campId 캠프 id
 * @param point  캠프 좌표
 */
public record CampDepot(UUID campId, GeoPoint point) {

    public CampDepot {
        Objects.requireNonNull(campId, "campId");
        Objects.requireNonNull(point, "point");
    }
}
