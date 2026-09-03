package com.dawnline.order.adapter.in.web;

import com.dawnline.common.error.ValidationException;
import com.dawnline.order.application.port.in.OrderCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * 커서 ↔ 불투명 문자열 (DESIGN.md §5.1).
 *
 * <h2>왜 불투명한가</h2>
 * 커서가 {@code (placedAt, id)} 라는 사실은 <strong>구현 세부</strong>다. 그대로 노출하면
 * 클라이언트가 커서를 손으로 만들기 시작하고, 그때부터 정렬 키를 바꾸는 것이 파괴적 변경이 된다.
 * base64url 로 감싸는 것은 암호가 아니라 <em>이 값을 해석하지 말라</em>는 신호다.
 *
 * <p>그래서 서명하지 않는다. 커서를 위조해도 얻을 수 있는 것은 "그 시각 이후의 자기 주문 목록"
 * 뿐이고, 그것은 {@code from}·{@code to} 파라미터로도 되는 일이다.
 */
public final class Cursors {

    private static final char SEPARATOR = '|';

    private Cursors() {
        throw new AssertionError("유틸리티 클래스는 생성하지 않는다");
    }

    /**
     * @param cursor 커서 위치
     * @return base64url 문자열 (패딩 없음)
     */
    public static String encode(OrderCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        String raw = cursor.placedAt().toString() + SEPARATOR + cursor.orderId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param encoded base64url 문자열
     * @return 커서 위치
     * @throws ValidationException 형식이 어긋나면 (400) — 400 이어야 하는 이유는 클라이언트가 고칠 수
     *                             있는 값이기 때문이다. 500 으로 새면 "우리 잘못" 처럼 보인다
     */
    public static OrderCursor decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        int separator = raw.indexOf(SEPARATOR);
        if (separator < 0) {
            throw invalid();
        }
        try {
            return new OrderCursor(
                    Instant.parse(raw.substring(0, separator)),
                    UUID.fromString(raw.substring(separator + 1)));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static ValidationException invalid() {
        // 디코딩한 내용은 응답에 넣지 않는다. 위조된 값을 그대로 되돌려 주면 반사 공격의 통로가 된다.
        return ValidationException.field("cursor", "", "커서 형식이 올바르지 않습니다");
    }
}
