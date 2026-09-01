package com.dawnline.messaging;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Kafka 레코드 헤더 이름과 W3C Trace Context 헬퍼 (DESIGN.md §4.2).
 *
 * <p>§4.2 는 {@code traceparent}(W3C) · {@code eventType} · {@code schemaVersion} 세 개만
 * 헤더에 중복 기록하라고 정한다. 목적은 <strong>페이로드를 열지 않고 라우팅·필터링</strong>이다.
 * 그래서 여기에 헤더를 더 추가하지 않는다 — 헤더가 늘면 봉투와 헤더 두 곳에 같은 사실이 생기고,
 * 반드시 어긋난다.
 *
 * <p>헤더 값은 모두 UTF-8 문자열이다. 숫자({@code schemaVersion})도 문자열로 쓴다.
 * 바이트 순서 해석이 컨슈머 언어마다 다르기 때문이다.
 */
public final class EventHeaders {

    /** W3C Trace Context 전파 헤더. 형식: {@code 00-<trace-id>-<span-id>-<flags>} */
    public static final String TRACEPARENT = "traceparent";

    /** 봉투의 {@code eventType} 중복 기록. */
    public static final String EVENT_TYPE = "eventType";

    /** 봉투의 {@code schemaVersion} 중복 기록(문자열). */
    public static final String SCHEMA_VERSION = "schemaVersion";

    /** {@code 00-} + trace-id(32) + {@code -} + span-id(16) + {@code -} + flags(2) */
    private static final Pattern TRACEPARENT_FORMAT =
            Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");

    /** trace-id 가 전부 0 이면 "컨텍스트 없음" 이다(W3C 사양상 무효). */
    private static final String INVALID_TRACE_ID = "0".repeat(32);

    private EventHeaders() {
    }

    /**
     * {@code traceparent} 헤더 값에서 trace-id(소문자 hex 32자)를 뽑는다.
     *
     * <p>형식이 어긋나거나 trace-id 가 전부 0 이면 비어 있는 값을 돌려준다.
     * 관측용 필드 때문에 이벤트가 DLQ 로 가면 안 되기 때문이다(contracts/events/README §4.2).
     *
     * @param traceparent W3C {@code traceparent} 헤더 값 (null 허용)
     */
    public static Optional<String> traceIdFrom(String traceparent) {
        if (traceparent == null) {
            return Optional.empty();
        }
        var matcher = TRACEPARENT_FORMAT.matcher(traceparent);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String traceId = matcher.group(1);
        return INVALID_TRACE_ID.equals(traceId) ? Optional.empty() : Optional.of(traceId);
    }

    /** 형식이 올바른 {@code traceparent} 인가. */
    public static boolean isValidTraceparent(String traceparent) {
        return traceIdFrom(traceparent).isPresent();
    }

    /** 헤더 값 → 바이트. Kafka 헤더는 바이트 배열이다. */
    public static byte[] toBytes(String value) {
        return Objects.requireNonNull(value, "value").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
