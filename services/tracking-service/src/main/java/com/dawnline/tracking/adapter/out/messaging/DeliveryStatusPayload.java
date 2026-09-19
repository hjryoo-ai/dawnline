package com.dawnline.tracking.adapter.out.messaging;

import com.dawnline.tracking.domain.ScanType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code delivery.status.v1} 페이로드
 * (계약: {@code contracts/events/delivery.status.v1.schema.json}).
 *
 * <p><strong>소비자가 먼저 정의한 계약</strong>이다 — order-service 가 Phase 1 에 썼고
 * ({@code contracts/events/README.md} 예외), tracking 이 Phase 5-1b 에서 처음 발행한다.
 * 그래서 이 레코드는 계약을 따라가지 계약을 정하지 않는다.
 *
 * @param routeId       라우트 id. 파티션 키와 같아야 한다 (§4.1)
 * @param stopSeq       stop 순번 ({@code route.assigned.v1} 의 {@code stops[].seq})
 * @param orderIds      이 stop 에서 상태가 옮겨진 주문들. 최소 하나다
 * @param status        {@code ARRIVED} · {@code COMPLETED} · {@code FAILED}
 * @param occurredAt    사건 시각 (ISO-8601)
 * @param failureReason {@code FAILED} 의 사유. 그 밖에는 {@code null} 이라 직렬화에서 빠진다
 */
public record DeliveryStatusPayload(UUID routeId, int stopSeq, List<String> orderIds,
        String status, String occurredAt, @Nullable String failureReason) {

    /** {@code eventType}. */
    public static final String EVENT_TYPE = "delivery.status";

    /** 페이로드 스키마 major. */
    public static final int SCHEMA_VERSION = 1;

    /** {@code outbox_events.aggregate_type}. 한 stop 의 여러 주문을 한 사건으로 싣는다. */
    public static final String AGGREGATE_TYPE = "Route";

    /**
     * 스캔 하나에서 만든다.
     *
     * @param routeId       라우트 id
     * @param stopSeq       stop 순번
     * @param orderIds      상태가 옮겨진 주문들
     * @param type          스캔 종류. {@link ScanType#isPublished()} 인 것만 온다
     * @param occurredAt    사건 시각
     * @param failureReason 실패 사유
     * @return 페이로드
     */
    public static DeliveryStatusPayload of(UUID routeId, int stopSeq, List<UUID> orderIds,
            ScanType type, Instant occurredAt, @Nullable String failureReason) {

        if (!type.isPublished()) {
            // 여기까지 오면 발행 규칙이 두 곳에 있다는 뜻이다. 계약의 status enum 에 없는 값이
            // 나가면 소비자는 그것을 무시하고(§4.7), 그 무시는 어디에도 나타나지 않는다.
            throw new IllegalArgumentException("발행 대상이 아닌 스캔입니다: " + type);
        }
        return new DeliveryStatusPayload(routeId, stopSeq,
                orderIds.stream().map(UUID::toString).toList(),
                type.name(), occurredAt.toString(), failureReason);
    }
}
