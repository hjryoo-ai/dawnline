package com.dawnline.tracking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 배송 사건 하나 (DESIGN.md §5.4 {@code shipment_events}).
 *
 * <p><strong>주문 단위</strong>다. 스캔은 stop 에서 한 번 일어나지만 stop 은 여러 주문을 묶으므로
 * (§6.5 {@code StopMerger}), 「주문 X 에 무슨 일이 있었나」에 답하려면 행이 주문마다 있어야 한다.
 *
 * <p><strong>상태를 옮긴 스캔만</strong> 행이 된다. 중복 스캔({@link ScanOutcome#STALE})과 취소
 * 뒤의 스캔({@link ScanOutcome#AFTER_CANCEL})은 기록하지 않는다 — 남기면 이 로그를 읽는 사람이
 * 「어느 행이 실제로 무언가를 바꿨나」를 알기 위해 상태 머신을 다시 구현해야 한다. 취소 뒤 스캔의
 * 기록은 {@code dawnline_scan_after_cancel_total} 이다(§5.4 「무시하되 센다」, §9.1).
 *
 * <p>{@code occurredAt} 은 <strong>파티션 키</strong>이기도 하다. 덮인 범위 밖의 값은 INSERT 가
 * 그 자리에서 실패한다 — DEFAULT 파티션을 두지 않은 결과이고, 그것이 의도다(§5.4).
 *
 * @param id            UUIDv7 (불변규칙 10)
 * @param orderId       주문 id
 * @param routeId       라우트 id
 * @param type          스캔 종류
 * @param occurredAt    사건 시각. 기사 단말이 말한 시각이지 우리가 처리한 시각이 아니다
 * @param lat           위도. 단말이 보내지 않았으면 {@code null}
 * @param lng           경도
 * @param failureReason {@code FAILED} 일 때의 사유({@code delivery.status.v1} 의 같은 이름 필드).
 *                      자유 텍스트라 계약이 200자로 제한한다 — 개인정보가 섞이지 않게 하려는 것이고,
 *                      같은 이유로 로그에 남기지 않는다 (§9.3)
 */
public record ShipmentEvent(UUID id, UUID orderId, UUID routeId, ScanType type, Instant occurredAt,
        @Nullable Double lat, @Nullable Double lng, @Nullable String failureReason) {

    public ShipmentEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
