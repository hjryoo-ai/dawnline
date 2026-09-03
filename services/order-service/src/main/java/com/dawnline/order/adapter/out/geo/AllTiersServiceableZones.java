package com.dawnline.order.adapter.out.geo;

import com.dawnline.order.domain.ServiceTier;
import com.dawnline.order.domain.TierEligibility;
import java.util.Set;

/**
 * Phase 1 의 권역 조회 — 좌표를 찾은 주소면 세 티어 모두 가능하다고 본다.
 *
 * <p>진짜 권역 데이터({@code zones} 테이블, §5.2)의 주인은 fulfillment-service 이고 Phase 2 에
 * 생긴다. 지금 그 판정을 흉내 내면 <strong>지어낸 사업 규칙</strong>이 코드에 남는다 —
 * "왜 이 동네는 새벽배송이 안 되지" 의 답이 아무 데도 없는 상태가 된다.
 *
 * <p>대신 실제 제약은 {@link PostalPrefixGeocoder} 가 건다. 수도권 밖 우편번호는 좌표를 찾지 못하고,
 * 주문은 접수 단계에서 거절된다. 즉 "서비스 지역" 판정은 이미 있고, 여기서 더할 것이 아직 없다.
 *
 * <p>이 클래스가 사라지는 시점은 fulfillment-service 가 {@code zones} 를 갖고 order-service 가
 * 그 스냅샷을 이벤트로 받는 때다(불변규칙 4 — 코어 서비스 간 동기 호출 금지).
 */
public class AllTiersServiceableZones implements TierEligibility.ServiceableZones {

    private static final Set<ServiceTier> ALL = Set.of(ServiceTier.values());

    @Override
    public Set<ServiceTier> supportedTiers(String geohash5) {
        return ALL;
    }
}
