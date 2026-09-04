package com.dawnline.fulfillment.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * FC 선택에 넘기는 후보 하나 — <strong>어댑터가 준비해서 넘긴다</strong>.
 *
 * <h2>거리와 재고가 왜 여기 들어 있는가</h2>
 * {@link FcSelection} 은 Spring 도 Redis 도 DB 도 모르는 순수 함수여야 한다(불변규칙 5).
 * 그러려면 판정에 필요한 값이 <em>이미 계산된 채로</em> 들어와야 한다. 캠프까지의 거리는 Redis
 * {@code GEOSEARCH}(§5.2 5단계)나 DB 폴백이 재고, 재고는 {@code inventory_stock} 조회가 준다.
 *
 * <p>{@code knownStock} 은 <strong>예외만</strong> 담는다. 재고 스텁의 규칙이 "행이 없으면 가용"
 * 이기 때문이다(§5.2 3단계, {@code R__seed_fulfillment.sql}). 즉 이 맵에 없는 SKU 는 충분히 있다는
 * 뜻이고, 있는 SKU 는 그 수량만큼만 있다는 뜻이다.
 *
 * @param id                  FC id
 * @param code                FC 코드
 * @param tiers               지원 티어 (§5.2 1단계)
 * @param supportsCold        냉장 지원 (§5.2 2단계)
 * @param active              운영 중인가
 * @param distanceFromCampKm  캠프까지의 거리(km). 대체 FC 선택에서만 쓴다 (ADR-021 결정 3-a)
 * @param knownStock          SKU → 가용 수량. <strong>없으면 가용</strong>
 */
public record CandidateFc(
        UUID id,
        String code,
        Set<ServiceTier> tiers,
        boolean supportsCold,
        boolean active,
        double distanceFromCampKm,
        Map<String, Integer> knownStock) {

    public CandidateFc {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        tiers = Set.copyOf(Objects.requireNonNull(tiers, "tiers"));
        knownStock = Map.copyOf(Objects.requireNonNull(knownStock, "knownStock"));
        if (distanceFromCampKm < 0) {
            throw new IllegalArgumentException("distanceFromCampKm 은 0 이상이어야 합니다: " + distanceFromCampKm);
        }
    }

    /** §5.2 1단계 — 이 티어를 지원하는가. */
    public boolean supports(ServiceTier tier) {
        return tiers.contains(tier);
    }

    /** §5.2 3단계 — 이 품목들을 충당할 재고가 있는가. 맵에 없는 SKU 는 가용으로 본다. */
    public boolean hasStockFor(Iterable<OrderLine> lines) {
        for (OrderLine line : lines) {
            Integer available = knownStock.get(line.sku());
            if (available != null && available < line.qty()) {
                return false;
            }
        }
        return true;
    }
}
