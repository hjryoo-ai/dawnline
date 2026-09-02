package com.dawnline.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TierEligibility — 접수 시점 티어 가능 여부 (DESIGN.md §5.1)")
class TierEligibilityTest {

    private static final DeliveryAddress GANGNAM =
            DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GeoPoint.of(37.4979, 127.0276));
    private static final DeliveryAddress JEJU =
            DeliveryAddress.of("제주 제주시 첨단로 1", "63309", GeoPoint.of(33.4507, 126.5706));

    /** 강남 권역은 전 티어, 제주 권역은 익일만. 그 밖은 서비스 불가. */
    private static TierEligibility eligibility() {
        Map<String, Set<ServiceTier>> table = Map.of(
                GANGNAM.geohash5(), EnumSet.allOf(ServiceTier.class),
                JEJU.geohash5(), EnumSet.of(ServiceTier.NEXT_DAY));
        return new TierEligibility(
                geohash5 -> table.getOrDefault(geohash5, Set.of()),
                Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void 지원_티어면_통과한다() {
        assertThat(eligibility().isEligible(GANGNAM, ServiceTier.DAWN)).isTrue();
    }

    @Test
    void 그_권역이_지원하지_않는_티어는_거른다() {
        // 접수 시점에 아는 것만으로 명백한 불가를 거른다. 받아 두면 고객은 접수 성공 응답을 받은 뒤에야
        // fulfillment 에서 unserviceable 로 되돌아온 것을 알게 된다.
        assertThat(eligibility().isEligible(JEJU, ServiceTier.DAWN)).isFalse();
        assertThat(eligibility().isEligible(JEJU, ServiceTier.NEXT_DAY)).isTrue();
    }

    @Test
    void 서비스_불가_지역은_어떤_티어도_안_된다() {
        DeliveryAddress unknown =
                DeliveryAddress.of("강원 양양군 어딘가 1", "25000", GeoPoint.of(38.0754, 128.6190));

        assertThat(eligibility().eligibleTiers(unknown)).isEmpty();
        for (ServiceTier tier : ServiceTier.values()) {
            assertThat(eligibility().isEligible(unknown, tier)).isFalse();
        }
    }

    @Test
    void eligibleTiers_는_거절_응답에_담을_대안을_준다() {
        // 사용자가 다음에 뭘 해야 할지 모르는 422 는 쓸모가 적다.
        assertThat(eligibility().eligibleTiers(JEJU)).containsExactly(ServiceTier.NEXT_DAY);
    }

    @Test
    void 서비스_기준_시간대는_서울이다() {
        // 컷오프·배송창이 모두 현지 시각으로 정의된다 (§2.2).
        // 2026-09-02T10:00Z = 19:00 KST.
        assertThat(eligibility().nowInServiceZone().getHour()).isEqualTo(19);
    }
}
