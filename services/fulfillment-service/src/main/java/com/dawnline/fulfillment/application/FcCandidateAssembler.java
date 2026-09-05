package com.dawnline.fulfillment.application;

import com.dawnline.fulfillment.application.port.out.FcDistances;
import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.CandidateFc;
import com.dawnline.fulfillment.domain.FulfillmentCenter;
import com.dawnline.fulfillment.domain.OrderLine;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 판정에 넘길 후보를 모은다 — 카탈로그 + 거리 + 재고 (§5.2).
 *
 * <p>{@link com.dawnline.fulfillment.domain.FcSelection} 이 순수 함수이려면 판정에 필요한 값이
 * <em>이미 계산된 채로</em> 들어와야 한다(불변규칙 5). 그 준비를 여기서 한다.
 *
 * <h2>판단은 하나도 하지 않는다</h2>
 * 반경으로 거르지도, 순위를 정하지도, 동률을 깨지도 않는다. 전부 판정 함수의 일이다 — Redis 로
 * 답했든 DB 폴백으로 답했든 <strong>같은 답</strong>이 나오려면 판단이 한 곳에만 있어야 한다.
 * 여기서 하는 일은 세 조회를 합치는 것뿐이다.
 */
public class FcCandidateAssembler {

    private static final Logger log = LoggerFactory.getLogger(FcCandidateAssembler.class);

    private final ReferenceData referenceData;
    private final FcDistances distances;

    /**
     * @param referenceData FC 카탈로그·재고
     * @param distances     캠프 기준 거리 (Redis 또는 폴백)
     */
    public FcCandidateAssembler(ReferenceData referenceData, FcDistances distances) {
        this.referenceData = Objects.requireNonNull(referenceData, "referenceData");
        this.distances = Objects.requireNonNull(distances, "distances");
    }

    /**
     * 후보 전체를 만든다. <strong>비활성 FC 도 포함한다</strong> — 판정이 그 사실을 봐야 한다.
     *
     * @param camp  기준 캠프
     * @param lines 주문 품목. 재고 조회 범위를 정한다
     */
    public List<CandidateFc> forCamp(Camp camp, Collection<OrderLine> lines) {
        Objects.requireNonNull(camp, "camp");
        Objects.requireNonNull(lines, "lines");

        List<FulfillmentCenter> centers = referenceData.findAllCenters();
        Set<UUID> ids = new LinkedHashSet<>(centers.size());
        centers.forEach(center -> ids.add(center.id()));

        Map<UUID, Double> km = distances.fromCamp(camp, ids);
        Map<UUID, Map<String, Integer>> stock = referenceData.findStock(skus(lines));

        List<CandidateFc> candidates = new ArrayList<>(centers.size());
        for (FulfillmentCenter center : centers) {
            candidates.add(center.asCandidate(
                    distanceOf(center, km), stock.getOrDefault(center.id(), Map.of())));
        }
        return List.copyOf(candidates);
    }

    /**
     * 거리를 못 얻은 FC 는 <strong>후보에서 빼지 않고 무한히 먼 것으로 둔다.</strong>
     *
     * <p>포트 계약상 여기 오면 안 되는 경우다(구현은 전부를 채우거나 폴백한다). 그래도 왔을 때
     * 후보에서 빼면 {@code UNSERVICEABLE} 사유가 조용히 달라진다 — "티어를 지원하는 FC 가 없다"
     * 는 답이 나올 수 있는데, 사실은 있고 거리만 모르는 것이다. 반경 안에 들지 못하게만 두면
     * 사유는 그대로이면서 대체 후보로도 뽑히지 않는다.
     */
    private static double distanceOf(FulfillmentCenter center, Map<UUID, Double> km) {
        Double value = km.get(center.id());
        if (value == null) {
            log.warn("FC {} 의 거리를 얻지 못했습니다. 반경 밖으로 둡니다.", center.code());
            return Double.MAX_VALUE;
        }
        return value;
    }

    private static Set<String> skus(Collection<OrderLine> lines) {
        Set<String> skus = new LinkedHashSet<>();
        lines.forEach(line -> skus.add(line.sku()));
        return skus;
    }
}
