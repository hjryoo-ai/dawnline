package com.dawnline.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 참조 데이터 FC → 판정 후보 변환. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FulfillmentCenterTest {

    private static final UUID ID = UUID.randomUUID();

    @Test
    void 후보로_바꾸면_거리와_재고가_붙는다() {
        // 좌표는 참조 데이터의 것이고 거리는 캠프마다 다르다 — 그래서 후보에만 있다.
        FulfillmentCenter center = new FulfillmentCenter(ID, "FC-A", new GeoPoint(37.5, 127.0),
                true, Set.of(ServiceTier.DAWN), true);

        CandidateFc candidate = center.asCandidate(12.5, Map.of("SKU-1", 0));

        assertThat(candidate.id()).isEqualTo(ID);
        assertThat(candidate.code()).isEqualTo("FC-A");
        assertThat(candidate.supportsCold()).isTrue();
        assertThat(candidate.active()).isTrue();
        assertThat(candidate.distanceFromCampKm()).isEqualTo(12.5);
        assertThat(candidate.supports(ServiceTier.DAWN)).isTrue();
        assertThat(candidate.hasStockFor(java.util.List.of(new OrderLine("SKU-1", 1)))).isFalse();
    }

    @Test
    void 비활성_FC_도_후보가_된다() {
        // 판정이 그 사실을 봐야 UNSERVICEABLE 사유가 맞는다.
        FulfillmentCenter closed = new FulfillmentCenter(ID, "FC-X", new GeoPoint(37.5, 127.0),
                false, Set.of(ServiceTier.DAWN), false);

        assertThat(closed.asCandidate(1.0, Map.of()).active()).isFalse();
    }
}
