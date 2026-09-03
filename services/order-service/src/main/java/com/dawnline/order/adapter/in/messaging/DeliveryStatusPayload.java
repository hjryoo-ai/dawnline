package com.dawnline.order.adapter.in.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code delivery.status} v1 중 order-service 가 읽는 필드만
 * (contracts/events/delivery.status.v1.schema.json).
 *
 * <p>{@code orderIds} 가 배열인 이유는 §6.2 의 stop 통합이다 — 같은 geohash7 격자의 주문들이 한
 * stop 으로 묶이므로 하나의 완료/실패가 여러 주문에 적용된다.
 *
 * @param routeId       라우트 (파티션 키)
 * @param stopSeq       stop 순번
 * @param orderIds      이 stop 에 묶인 주문들
 * @param status        {@code ARRIVED}·{@code COMPLETED}·{@code FAILED}
 * @param occurredAt    사건 시각. 상태 전이 시각으로 쓴다 (§8.1 정시율이 이 값을 본다)
 * @param failureReason {@code FAILED} 일 때의 사유. 없을 수 있다
 */
public record DeliveryStatusPayload(
        UUID routeId,
        int stopSeq,
        List<UUID> orderIds,
        String status,
        Instant occurredAt,
        @Nullable String failureReason) {

    /** 배송 완료. */
    public static final String COMPLETED = "COMPLETED";

    /** 배송 실패. */
    public static final String FAILED = "FAILED";

    /**
     * 도착. 주문 상태 머신에는 대응하는 상태가 없다 — 도착은 배송 진행 정보이지 주문 상태가 아니다.
     * 그래도 이벤트는 정상 소비하고 커밋한다(그러지 않으면 계속 다시 온다).
     */
    public static final String ARRIVED = "ARRIVED";
}
