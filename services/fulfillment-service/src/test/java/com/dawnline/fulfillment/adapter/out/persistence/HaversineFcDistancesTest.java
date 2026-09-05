package com.dawnline.fulfillment.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.FulfillmentCenter;
import com.dawnline.fulfillment.domain.GeoDistance;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.Zone;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** DB 폴백 거리 (§7.2). Redis 와의 동등성은 {@code GeoEquivalenceIT} 가 본다. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HaversineFcDistancesTest {

    private static final UUID NEAR = UUID.randomUUID();
    private static final UUID FAR = UUID.randomUUID();
    private static final Camp CAMP = new Camp(UUID.randomUUID(), "CAMP-A", NEAR,
            new GeoPoint(37.50, 127.00), true);

    private final HaversineFcDistances distances = new HaversineFcDistances(new StubCatalog());

    @Test
    void 요청한_FC_만_돌려준다() {
        assertThat(distances.fromCamp(CAMP, List.of(NEAR))).containsOnlyKeys(NEAR);
    }

    @Test
    void 거리는_하버사인과_같다() {
        double expected = GeoDistance.km(CAMP.location(), new GeoPoint(37.90, 127.60));

        assertThat(distances.fromCamp(CAMP, List.of(NEAR, FAR)).get(FAR)).isEqualTo(expected);
    }

    @Test
    void 카탈로그에_없는_id_는_빠진다() {
        // 어댑터가 없는 값을 지어내지 않는다. 빠졌다는 사실은 호출부가 본다.
        assertThat(distances.fromCamp(CAMP, List.of(UUID.randomUUID()))).isEmpty();
    }

    /** FC 두 곳뿐인 카탈로그. */
    private static final class StubCatalog implements ReferenceData {

        @Override
        public Optional<Zone> findZone(String geohash5) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Camp> findCamp(UUID campId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<FulfillmentCenter> findAllCenters() {
            return List.of(
                    new FulfillmentCenter(NEAR, "FC-NEAR", new GeoPoint(37.51, 127.01), true,
                            Set.of(ServiceTier.DAWN), true),
                    new FulfillmentCenter(FAR, "FC-FAR", new GeoPoint(37.90, 127.60), false,
                            Set.of(ServiceTier.DAWN), true));
        }

        @Override
        public List<Camp> findAllCamps() {
            return List.of(CAMP);
        }

        @Override
        public Map<UUID, Map<String, Integer>> findStock(Collection<String> skus) {
            throw new UnsupportedOperationException();
        }
    }
}
