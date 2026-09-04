package com.dawnline.order.application.port.out;

import com.dawnline.order.application.port.in.OrderAccepted;
import java.util.Objects;

/**
 * {@code idempotency_keys} 한 행 (DESIGN.md §5.1, ADR-018·019).
 *
 * <p><strong>행이 있다는 것은 곧 완료다.</strong> 처리 중 상태는 이 테이블에 없다 — 그 표시는
 * 30초 뒤 스스로 풀리는 Redis 키가 맡는다. 그래서 여기에는 상태 필드가 없고 응답이 항상 있다.
 *
 * <p>JSON 은 여기까지 올라오지 않는다. {@code response_body} 를 {@link OrderAccepted} 로 되돌리는
 * 것은 어댑터의 일이다 — 저장 형식은 어댑터가 정하고, 유스케이스는 "그때 준 답" 만 본다.
 *
 * @param requestHash  저장된 요청 지문. 지금 요청의 지문과 다르면 422 다
 * @param responseCode 저장된 HTTP 상태 코드
 * @param response     저장된 응답
 */
public record IdempotencyRecord(String requestHash, int responseCode, OrderAccepted response) {

    public IdempotencyRecord {
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(response, "response");
    }
}
