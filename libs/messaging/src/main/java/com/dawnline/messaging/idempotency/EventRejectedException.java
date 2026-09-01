package com.dawnline.messaging.idempotency;

import java.util.Objects;

/**
 * 비즈니스 규칙상 처리할 수 없어 <strong>무시</strong>하는 이벤트 (DESIGN.md §4.6).
 *
 * <p>§4.6 표의 세 번째 줄이다: "비즈니스 규칙 위반 (예: 취소 불가 상태) → DLQ 아님. 무시하고
 * {@code warn} 로그 + 메트릭". 재시도해도 결과가 달라지지 않으므로 재시도하지 않고, 운영자가 손댈 것도
 * 없으므로 DLQ 로도 보내지 않는다.
 *
 * <p>{@link IdempotentConsumer} 가 이 예외를 잡아 {@code outcome=rejected} 로 기록하고
 * 트랜잭션을 <em>커밋</em>한다. 즉 {@code processed_events} 행은 남고 같은 이벤트가 다시 오지 않는다.
 *
 * <p><strong>계약</strong>: 이 예외는 <em>어떤 상태도 바꾸기 전에</em> 던져야 한다. 커밋되기 때문이다.
 * 도메인 검증은 변경보다 먼저 하라는 일반 규칙과 같은 이야기다.
 *
 * <p>재시도가 의미 있는 일시적 오류(DB 타임아웃, Redis 연결)는 이 예외가 아니라 원래 예외를 그대로
 * 던져 올린다. 그러면 §4.6 첫 줄의 지수 백오프 재시도 경로로 간다.
 */
public class EventRejectedException extends RuntimeException {

    private final String reason;

    /**
     * @param reason  메트릭 태그가 되는 <strong>낮은 카디널리티</strong> 사유 코드.
     *                예: {@code ORDER_ALREADY_DISPATCHED}. 주문 id 같은 값을 넣으면 안 된다.
     * @param message 사람이 읽는 설명
     */
    public EventRejectedException(String reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * @param reason  낮은 카디널리티 사유 코드
     * @param message 사람이 읽는 설명
     * @param cause   원인
     */
    public EventRejectedException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** {@code dawnline_event_rejected_total{reason}} 의 태그 값 (§4.6). */
    public String reason() {
        return reason;
    }
}
