package com.dawnline.fulfillment.domain;

import com.dawnline.common.GeoPoint;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 물류센터 (DESIGN.md §5.2 {@code fulfillment_centers}).
 *
 * <p>{@link CandidateFc} 와 다르다. 이쪽은 <strong>참조 데이터 그 자체</strong>(좌표를 포함한다)이고,
 * 저쪽은 <em>한 번의 판정에 넘기는 후보</em>(캠프까지의 거리와 재고가 채워져 있다)다. 거리는 캠프마다
 * 다르므로 참조 데이터에 들어갈 수 없다.
 *
 * @param id           FC id
 * @param code         FC 코드. <strong>동률일 때의 순위를 정하는 값</strong>이기도 하다 (§5.2 5단계)
 * @param location     좌표. Redis {@code geo:fc} 적재와 폴백 하버사인이 모두 이 값을 쓴다
 * @param supportsCold 냉장 지원
 * @param tiers        지원 티어
 * @param active       운영 중인가
 */
public record FulfillmentCenter(
        UUID id,
        String code,
        GeoPoint location,
        boolean supportsCold,
        Set<ServiceTier> tiers,
        boolean active) {

    public FulfillmentCenter {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(location, "location");
        tiers = Set.copyOf(Objects.requireNonNull(tiers, "tiers"));
    }

    /**
     * 판정에 넘길 후보로 만든다.
     *
     * @param distanceFromCampKm 캠프까지의 거리 (Redis GEO 또는 폴백 하버사인)
     * @param knownStock         SKU → 가용 수량. 없으면 가용
     */
    public CandidateFc asCandidate(double distanceFromCampKm, java.util.Map<String, Integer> knownStock) {
        return new CandidateFc(id, code, tiers, supportsCold, active, distanceFromCampKm, knownStock);
    }
}
