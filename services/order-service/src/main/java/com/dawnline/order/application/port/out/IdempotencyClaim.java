package com.dawnline.order.application.port.out;

import com.dawnline.order.application.port.in.PlaceOrderCommand;
import java.time.Instant;
import java.util.Objects;

/**
 * 하나의 멱등 키에 대한 완료 기록을 쓰기 위한 값 (DESIGN.md §5.1, ADR-018).
 *
 * <p>키와 지문을 한 덩어리로 묶는 이유는 둘 다 문자열이라서다. 인자로 나란히 넘기면 순서를 바꿔
 * 써도 컴파일되고, 그 결과는 "모든 요청이 서로의 응답을 재생하는" 조용한 오작동이다.
 *
 * @param key         멱등 키 ({@code idem_key})
 * @param requestHash 요청 지문 SHA-256 16진수 ({@code request_hash} CHAR(64))
 * @param createdAt   기록 시각
 * @param expiresAt   보관 만료. Redis 키 TTL(24h)과 별개인 DB 쪽 정리 기준이다
 */
public record IdempotencyClaim(String key, String requestHash, Instant createdAt, Instant expiresAt) {

    /** {@code request_hash} 는 CHAR(64) — SHA-256 을 16진수로 적은 길이다. */
    public static final int HASH_LENGTH = 64;

    public IdempotencyClaim {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (key.isBlank() || key.length() > PlaceOrderCommand.MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "key 길이는 1.." + PlaceOrderCommand.MAX_IDEMPOTENCY_KEY_LENGTH + " 여야 합니다: " + key.length());
        }
        if (requestHash.length() != HASH_LENGTH) {
            // 키와 지문을 바꿔 넣으면 거의 항상 여기서 걸린다.
            throw new IllegalArgumentException(
                    "requestHash 는 " + HASH_LENGTH + "자여야 합니다: " + requestHash.length());
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt 은 createdAt 이후여야 합니다");
        }
    }
}
