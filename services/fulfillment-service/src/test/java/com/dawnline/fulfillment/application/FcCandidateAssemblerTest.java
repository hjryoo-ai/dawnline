package com.dawnline.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.fulfillment.application.port.out.FcDistances;
import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.CandidateFc;
import com.dawnline.fulfillment.domain.FulfillmentCenter;
import com.dawnline.fulfillment.domain.OrderLine;
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

/** 후보 조립 — 세 조회를 합치는 것 <em>말고는</em> 아무 판단도 하지 않는지. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FcCandidateAssemblerTest {

    private static final UUID NEAR = UUID.randomUUID();
    private static final UUID FAR = UUID.randomUUID();
    private static final UUID CLOSED = UUID.randomUUID();

    private static final Camp CAMP = new Camp(UUID.randomUUID(), "CAMP-A", NEAR,
            new GeoPoint(37.50, 127.00), true);

    private static final List<FulfillmentCenter> CENTERS = List.of(
            new FulfillmentCenter(NEAR, "FC-NEAR", new GeoPoint(37.51, 127.01), true,
                    Set.of(ServiceTier.DAWN), true),
            new FulfillmentCenter(FAR, "FC-FAR", new GeoPoint(37.90, 127.60), false,
                    Set.of(ServiceTier.DAWN, ServiceTier.SAME_DAY), true),
            new FulfillmentCenter(CLOSED, "FC-CLOSED", new GeoPoint(37.52, 127.02), true,
                    Set.of(ServiceTier.DAWN), false));

    private final Stub referenceData = new Stub();

    private FcCandidateAssembler assembler(FcDistances distances) {
        return new FcCandidateAssembler(referenceData, distances);
    }

    @Test
    void 비활성_FC_도_후보에_넣는다() {
        // 판정이 그 사실을 봐야 한다. 여기서 빼면 UNSERVICEABLE 사유가 달라진다.
        List<CandidateFc> candidates = assembler(distances(Map.of(NEAR, 1.4, FAR, 70.0, CLOSED, 2.0)))
                .forCamp(CAMP, List.of(new OrderLine("SKU-00001", 1)));

        assertThat(candidates).extracting(CandidateFc::code)
                .containsExactly("FC-NEAR", "FC-FAR", "FC-CLOSED");
        assertThat(candidates).filteredOn(fc -> fc.code().equals("FC-CLOSED"))
                .singleElement().extracting(CandidateFc::active).isEqualTo(false);
    }

    @Test
    void 반경으로_거르지_않는다() {
        // 50 km 는 판정(FcSelection)의 일이다. 여기서 거르면 "티어를 지원하는 FC 가 없다" 와
        // "있지만 너무 멀다" 가 구별되지 않는다.
        List<CandidateFc> candidates = assembler(distances(Map.of(NEAR, 1.4, FAR, 70.0, CLOSED, 2.0)))
                .forCamp(CAMP, List.of(new OrderLine("SKU-00001", 1)));

        assertThat(candidates).anyMatch(fc -> fc.distanceFromCampKm() > 50.0);
    }

    @Test
    void 재고는_주문에_실린_SKU_만_조회한다() {
        assembler(distances(Map.of(NEAR, 1.4, FAR, 70.0, CLOSED, 2.0)))
                .forCamp(CAMP, List.of(new OrderLine("SKU-00013", 2), new OrderLine("SKU-00013", 1)));

        assertThat(referenceData.requestedSkus).containsExactly("SKU-00013");
    }

    @Test
    void 재고_행이_없는_FC_는_빈_맵을_받는다() {
        // 스텁의 규칙이 "행이 없으면 가용" 이다 (§5.2 3단계).
        referenceData.stock = Map.of(NEAR, Map.of("SKU-00013", 0));

        List<CandidateFc> candidates = assembler(distances(Map.of(NEAR, 1.4, FAR, 70.0, CLOSED, 2.0)))
                .forCamp(CAMP, List.of(new OrderLine("SKU-00013", 1)));

        assertThat(candidates).filteredOn(fc -> fc.code().equals("FC-NEAR"))
                .singleElement().matches(fc -> !fc.hasStockFor(List.of(new OrderLine("SKU-00013", 1))));
        assertThat(candidates).filteredOn(fc -> fc.code().equals("FC-FAR"))
                .singleElement().matches(fc -> fc.hasStockFor(List.of(new OrderLine("SKU-00013", 1))));
    }

    @Test
    void 거리를_못_얻은_FC_는_빼지_않고_무한히_멀게_둔다() {
        // 포트 계약상 오면 안 되는 경우다. 그래도 왔을 때 후보에서 빼면 UNSERVICEABLE 사유가
        // 조용히 달라진다 — 사실은 있고 거리만 모르는 것이다.
        List<CandidateFc> candidates = assembler(distances(Map.of(NEAR, 1.4)))
                .forCamp(CAMP, List.of(new OrderLine("SKU-00001", 1)));

        assertThat(candidates).hasSize(3);
        assertThat(candidates).filteredOn(fc -> fc.code().equals("FC-FAR"))
                .singleElement().extracting(CandidateFc::distanceFromCampKm).isEqualTo(Double.MAX_VALUE);
    }

    private static FcDistances distances(Map<UUID, Double> km) {
        return (camp, fcIds) -> km;
    }

    /** 참조 데이터 스텁. 조회된 SKU 를 기록한다. */
    private static final class Stub implements ReferenceData {

        private Set<String> requestedSkus = Set.of();
        private Map<UUID, Map<String, Integer>> stock = Map.of();

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
            return CENTERS;
        }

        @Override
        public List<Camp> findAllCamps() {
            return List.of(CAMP);
        }

        @Override
        public Map<UUID, Map<String, Integer>> findStock(Collection<String> skus) {
            requestedSkus = Set.copyOf(skus);
            return stock;
        }
    }
}
