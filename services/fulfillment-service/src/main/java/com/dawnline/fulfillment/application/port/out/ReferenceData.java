package com.dawnline.fulfillment.application.port.out;

import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.FulfillmentCenter;
import com.dawnline.fulfillment.domain.Zone;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 참조 데이터 조회 — FC·캠프·권역·재고 (DESIGN.md §5.2, 부록 A).
 *
 * <p>{@code R__seed_fulfillment.sql} 이 넣는 값들이고 거의 바뀌지 않는다. 규모도 작다
 * (FC 3 · 캠프 10 · 권역 91). <strong>그 작음이 §7.2 의 GEO 폴백을 값싸게 만드는 근거</strong>이고,
 * 규모가 크게 달라지면 그 판단을 다시 봐야 한다([ADR-016](docs/adr/ADR-016-readiness-excludes-kafka.md)
 * 후속 정정의 마지막 문단).
 */
public interface ReferenceData {

    /**
     * geohash5 셀이 속한 권역.
     *
     * <p>비어 있으면 {@code UNSERVICEABLE(NO_ZONE_MATCH)} 다 — 서비스하지 않는 지역이라는 뜻이고,
     * 시드가 지오코더의 출력을 덮지 못해서가 아니어야 한다(ADR-021, `ZoneSeedCoverageIT`).
     *
     * @param geohash5 5자 셀
     */
    Optional<Zone> findZone(String geohash5);

    /**
     * 캠프.
     *
     * @param campId 캠프 id
     */
    Optional<Camp> findCamp(UUID campId);

    /** 모든 FC. 비활성도 포함한다 — 판정({@code FcSelection})이 그 사실을 봐야 한다. */
    List<FulfillmentCenter> findAllCenters();

    /** 모든 캠프. {@code geo:camp} 적재에 쓴다. */
    List<Camp> findAllCamps();

    /**
     * 재고 예외 조회 — <strong>행이 없으면 가용</strong> (§5.2 3단계).
     *
     * <p>스텁의 규칙이 그렇다. 2,000개 SKU × FC 3개를 전부 적어 두면 "이 주문은 왜
     * {@code OUT_OF_STOCK} 인가" 의 답이 6,000행 어딘가에 묻힌다.
     *
     * @param skus 주문에 실린 SKU 들
     * @return FC id → (SKU → 가용 수량). 값이 없는 FC·SKU 는 가용이다
     */
    Map<UUID, Map<String, Integer>> findStock(Collection<String> skus);
}
