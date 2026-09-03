package com.dawnline.order.application.port.out;

import com.dawnline.order.application.port.in.OrderAccepted;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * {@code idempotency_keys} 한 행 (DESIGN.md §5.1).
 *
 * <p>JSON 은 여기까지 올라오지 않는다. {@code response_body} 를 {@link OrderAccepted} 로 되돌리는
 * 것은 어댑터의 일이다 — 저장 형식은 어댑터가 정하고, 유스케이스는 "그때 준 답" 만 본다.
 *
 * @param requestHash  저장된 요청 지문. 지금 요청의 지문과 다르면 422 다
 * @param status       상태
 * @param responseCode 저장된 HTTP 상태 코드. {@link IdempotencyStatus#DONE} 일 때만 있다
 * @param response     저장된 응답. {@link IdempotencyStatus#DONE} 일 때만 있다
 */
public record IdempotencyRecord(
        String requestHash,
        IdempotencyStatus status,
        @Nullable Integer responseCode,
        @Nullable OrderAccepted response) {

    public IdempotencyRecord {
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(status, "status");
        if (status == IdempotencyStatus.DONE && response == null) {
            // DONE 인데 재생할 응답이 없으면 그 행은 쓸모가 없다. 읽는 쪽에서 널 검사를 흩뿌리는 대신
            // 여기서 막는다 — 이 상태는 저장 경로의 버그이지 정상적인 값이 아니다.
            throw new IllegalArgumentException("DONE 기록에는 응답이 있어야 합니다: " + requestHash);
        }
    }
}
