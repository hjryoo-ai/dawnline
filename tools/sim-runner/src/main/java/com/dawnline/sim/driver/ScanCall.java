package com.dawnline.sim.driver;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 스캔 API 호출 하나 — {@code POST /api/v1/routes/{routeId}/stops/{stopSeq}/events} 의 본문과
 * 경로 변수 (DESIGN.md §5.4).
 *
 * <p>{@link DriverSimulator} 의 <strong>출력이자 이 도구의 계약면</strong>이다. 값만 담고
 * 아무것도 실행하지 않으므로, 「이 라우트를 이렇게 돌 것이다」를 HTTP 없이 어설션할 수 있다.
 *
 * @param stopSeq       stop 순번. 서버에서는 <strong>확인용</strong>이다(ADR-047 결정 1) —
 *                      경로 변수라 값이 있어야 하고, {@code DEPARTED_CAMP} 는 첫 stop 의 순번을 쓴다
 * @param orderIds      찍는 송장들 — <strong>이 호출의 열쇠</strong>다. {@code DEPARTED_CAMP} 만
 *                      비운다: 캠프 출발은 라우트의 사건이라 열쇠가 라우트이고, 실어 보내면
 *                      tracking 이 400 으로 답한다
 * @param type          스캔 종류
 * @param occurredAt    <strong>시뮬레이션 시각</strong>. 계획 시각 + 주입한 지연이며 벽시계가
 *                      아니다 (불변규칙 12, {@code package-info} 참고)
 * @param lat           위도. 계약에 좌표가 없는 stop 이면 {@code null}
 * @param lng           경도
 * @param failureReason {@code FAILED} 에만 있다. 다른 종류에 붙이면 tracking 이 400 으로 답한다
 */
public record ScanCall(int stopSeq, List<UUID> orderIds, ScanType type, Instant occurredAt,
        @Nullable Double lat, @Nullable Double lng, @Nullable String failureReason) {

    public ScanCall {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        orderIds = List.copyOf(orderIds);
        if (stopSeq < 1) {
            throw new IllegalArgumentException("stopSeq 는 1 이상이어야 합니다: " + stopSeq);
        }
        if (failureReason != null && type != ScanType.FAILED) {
            throw new IllegalArgumentException("failureReason 은 FAILED 에만 붙습니다: " + type);
        }
        // 서버의 규칙을 여기서도 말한다 — 어기면 400 이고, 그 400 은 이 도구의 결함이지
        // 시나리오의 결과가 아니다. 그런 요청은 보내기 전에 터지는 편이 읽기 쉽다.
        boolean fromCamp = type == ScanType.DEPARTED_CAMP;
        if (fromCamp != orderIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "orderIds 는 DEPARTED_CAMP 에만 비어 있습니다: type=%s, 주문 %d 건"
                            .formatted(type, orderIds.size()));
        }
    }
}
