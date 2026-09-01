package com.dawnline.messaging.kafka;

/**
 * 재시도해도 결과가 같은 실패 — 즉시 DLQ 로 보낸다 (DESIGN.md §4.6).
 *
 * <p>§4.6 표의 두 번째 줄("역직렬화 실패/스키마 불일치 → 즉시 DLQ + 알림")에 해당하는 사례를
 * 리스너 코드가 직접 표현하고 싶을 때 쓴다. Jackson 파싱 실패는 굳이 이 예외로 감싸지 않아도
 * {@code DawnlineErrorHandlers} 가 이미 재시도 대상에서 제외한다.
 *
 * <p><strong>비즈니스 규칙 위반과 혼동하지 말 것.</strong> 그쪽은
 * {@code com.dawnline.messaging.idempotency.EventRejectedException} 이고 DLQ 로 가지 않는다.
 * 여기로 오는 것은 "메시지 자체가 잘못돼서 사람이 봐야 하는" 경우다.
 */
public class NonRetryableEventException extends RuntimeException {

    /**
     * @param message 설명
     */
    public NonRetryableEventException(String message) {
        super(message);
    }

    /**
     * @param message 설명
     * @param cause   원인
     */
    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
