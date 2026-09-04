package com.dawnline.fulfillment.domain;

import com.dawnline.common.GeoPoint;
import java.util.Objects;
import java.util.UUID;

/**
 * 배송 캠프 (DESIGN.md §5.2 {@code camps}).
 *
 * <p>라스트마일의 출발점이다. 대체 FC 선택에서 거리를 재는 기준점이 고객 주소가 아니라 캠프인
 * 이유가 그것이다 — 어느 FC 를 쓰든 배송은 캠프에서 출발하므로 달라지는 비용은 FC → 캠프
 * 간선(linehaul)뿐이다 (ADR-021 결정 3-a).
 *
 * @param id        캠프 id
 * @param code      캠프 코드
 * @param homeFcId  홈 FC. 1~3단계 필터를 통과하면 그대로 쓴다
 * @param location  좌표
 * @param active    운영 중인가
 */
public record Camp(UUID id, String code, UUID homeFcId, GeoPoint location, boolean active) {

    public Camp {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(homeFcId, "homeFcId");
        Objects.requireNonNull(location, "location");
    }
}
