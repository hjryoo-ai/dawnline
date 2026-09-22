package com.dawnline.sim.driver;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 스캔 API 호출 하나 — {@code POST /api/v1/routes/{routeId}/stops/{stopSeq}/events} 의 본문과
 * 경로 변수 (DESIGN.md §5.4).
 *
 * <p>{@link DriverSimulator} 의 <strong>출력이자 이 도구의 계약면</strong>이다. 값만 담고
 * 아무것도 실행하지 않으므로, 「이 라우트를 이렇게 돌 것이다」를 HTTP 없이 어설션할 수 있다.
 *
 * @param stopSeq       stop 순번. {@code DEPARTED_CAMP} 에서는 서버가 무시하지만 경로 변수라
 *                      값이 있어야 한다 — 첫 stop 의 순번을 쓴다
 * @param type          스캔 종류
 * @param occurredAt    <strong>시뮬레이션 시각</strong>. 계획 시각 + 주입한 지연이며 벽시계가
 *                      아니다 (불변규칙 12, {@code package-info} 참고)
 * @param lat           위도. 계약에 좌표가 없는 stop 이면 {@code null}
 * @param lng           경도
 * @param failureReason {@code FAILED} 에만 있다. 다른 종류에 붙이면 tracking 이 400 으로 답한다
 */
public record ScanCall(int stopSeq, ScanType type, Instant occurredAt,
        @Nullable Double lat, @Nullable Double lng, @Nullable String failureReason) {

    public ScanCall {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (stopSeq < 1) {
            throw new IllegalArgumentException("stopSeq 는 1 이상이어야 합니다: " + stopSeq);
        }
        if (failureReason != null && type != ScanType.FAILED) {
            throw new IllegalArgumentException("failureReason 은 FAILED 에만 붙습니다: " + type);
        }
    }
}
