package com.dawnline.fulfillment.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 권역 (DESIGN.md §5.2 {@code zones}, [ADR-021](docs/adr/ADR-021-zone-seed-derived-from-geocoder.md)).
 *
 * <p>geohash5 셀 하나가 권역 하나다(부록 C — 약 4.9 km 셀). 이 집합이 order-service 지오코더의
 * 출력을 전부 덮어야 한다 — 덮지 못한 셀의 주소는 {@code UNSERVICEABLE(NO_ZONE_MATCH)} 가 되는데,
 * 그것이 설계된 실패 경로와 <em>같은 값</em>이라 로그만으로는 구별되지 않는다.
 *
 * @param id       권역 id
 * @param geohash5 geohash5 셀 (5자)
 * @param campId   이 권역을 담당하는 캠프
 */
public record Zone(UUID id, String geohash5, UUID campId) {

    public Zone {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(campId, "campId");
        Objects.requireNonNull(geohash5, "geohash5");
        if (geohash5.length() != 5) {
            throw new IllegalArgumentException("geohash5 는 5자여야 합니다: " + geohash5);
        }
    }
}
