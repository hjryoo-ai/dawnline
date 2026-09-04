package com.dawnline.fulfillment.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * FC 선택에 필요한 주문 정보 (DESIGN.md §5.2 1~6단계).
 *
 * <p>{@code order.placed} 페이로드의 부분집합이다. 주소·약속창은 여기 없다 — FC 선택은 그것들을
 * 보지 않는다. 필요한 것만 넘기면 이 함수가 무엇에 의존하는지가 서명에 드러난다.
 *
 * @param orderId      주문 id
 * @param serviceTier  서비스 티어 (1단계 필터)
 * @param requiresCold 냉장 필요 (2단계 필터)
 * @param lines        품목 (3단계 필터)
 * @param cutoffAt     order-service 가 계산한 컷오프 (ADR-020). {@code STALE_PLACED} 판정의 기준
 */
public record OrderToPlan(
        UUID orderId,
        ServiceTier serviceTier,
        boolean requiresCold,
        List<OrderLine> lines,
        Instant cutoffAt) {

    public OrderToPlan {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(serviceTier, "serviceTier");
        Objects.requireNonNull(cutoffAt, "cutoffAt");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("lines 는 1건 이상이어야 합니다");
        }
    }
}
