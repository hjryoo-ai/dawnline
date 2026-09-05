package com.dawnline.fulfillment.application.port.out;

import com.dawnline.fulfillment.domain.Camp;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 캠프에서 각 FC 까지의 거리 (§5.2 5단계, §7.2 {@code geo:fc}).
 *
 * <p><strong>거리만 준다.</strong> 반경 상한도, 최근접 선택도, 동률 처리도 이 포트의 일이 아니다 —
 * 전부 {@link com.dawnline.fulfillment.domain.FcSelection} 안에 있다. 구현이 Redis 든 DB 든 같은
 * 답이 나와야 하고, 그러려면 <em>판단</em>이 어댑터에 흩어져 있으면 안 되기 때문이다.
 *
 * <p>그래서 반환도 "반경 안의 FC" 가 아니라 <strong>요청한 전부</strong>다. 어댑터가 50 km 로 먼저
 * 거르면 반경 밖 FC 가 판정에서 사라지고, 그러면 {@code UNSERVICEABLE} 사유가 달라진다 —
 * "이 티어를 지원하는 FC 가 없다" 와 "있지만 너무 멀다" 는 다른 사실이다.
 *
 * <p>호출부가 FC id 를 넘기는 이유도 같다. 구현은 <strong>그 전부를 채우거나 폴백해야</strong>
 * 하고, 하나라도 빠진 채로 돌려주면 그 FC 는 조용히 후보에서 사라진다. 개수가 아니라 id 로
 * 확인하는 편이 정확하고, 호출부가 이미 카탈로그를 읽었으므로 추가 조회도 없다.
 */
public interface FcDistances {

    /**
     * 캠프 기준 거리(km).
     *
     * @param camp  기준 캠프. 라스트마일이 여기서 출발하므로 대체 FC 선택에서 달라지는 비용은
     *              FC → 캠프 간선뿐이다 (ADR-021 결정 3-a)
     * @param fcIds 거리를 알아야 하는 FC 들. 구현은 <strong>전부를 채우거나</strong> 폴백한다
     * @return FC id → 거리(km)
     */
    Map<UUID, Double> fromCamp(Camp camp, Collection<UUID> fcIds);
}
