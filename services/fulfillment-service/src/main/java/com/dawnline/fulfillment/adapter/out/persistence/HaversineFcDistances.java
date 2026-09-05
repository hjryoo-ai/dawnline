package com.dawnline.fulfillment.adapter.out.persistence;

import com.dawnline.fulfillment.application.port.out.FcDistances;
import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.FulfillmentCenter;
import com.dawnline.fulfillment.domain.GeoDistance;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@link FcDistances} 의 DB 폴백 — 전체 조회 + 메모리 하버사인 (§7.2 폴백 표).
 *
 * <p>Redis 가 없어도 <strong>같은 답</strong>이 나와야 한다(불변규칙 7). "같은 답" 이 "같은 숫자" 는
 * 아니다 — Redis 는 좌표를 52비트 geohash 로 양자화하므로 수 m 이 어긋난다. 같아야 하는 것은
 * <em>순위</em>이고, 그래서 {@link GeoDistance} 는 Redis 와 같은 지구 반지름을 쓰고 동률 처리는
 * 판정 함수 안에 있다.
 *
 * <p>이 폴백이 값싼 이유는 FC 가 3개이기 때문이다. 그 수가 크게 늘면
 * [ADR-016](docs/adr/ADR-016-readiness-excludes-kafka.md) 후속 정정의 마지막 문단대로 이 비용을
 * 다시 재야 한다.
 */
public class HaversineFcDistances implements FcDistances {

    private final ReferenceData referenceData;

    /**
     * @param referenceData FC 좌표 출처
     */
    public HaversineFcDistances(ReferenceData referenceData) {
        this.referenceData = Objects.requireNonNull(referenceData, "referenceData");
    }

    @Override
    public Map<UUID, Double> fromCamp(Camp camp, Collection<UUID> fcIds) {
        Objects.requireNonNull(camp, "camp");
        Set<UUID> wanted = Set.copyOf(Objects.requireNonNull(fcIds, "fcIds"));
        Map<UUID, Double> distances = new LinkedHashMap<>();
        for (FulfillmentCenter center : referenceData.findAllCenters()) {
            if (wanted.contains(center.id())) {
                distances.put(center.id(), GeoDistance.km(camp.location(), center.location()));
            }
        }
        return Map.copyOf(distances);
    }
}
