package com.dawnline.messaging;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 토픽 이름 규칙 (DESIGN.md §4.1).
 *
 * <p>{@code dawnline.<eventType>.v<major>} 와 DLQ {@code <topic>.dlq} 뿐이다.
 * 새 토픽을 여기서 상수로 정의하지 않는다 — 토픽 목록은 설계서(§4.1)와
 * {@code deploy/compose} 의 토픽 생성 스크립트가 진실이고, 이 클래스는 <em>이름을 만드는 규칙</em>만 담는다.
 */
public final class Topics {

    /** 모든 토픽의 접두어. */
    public static final String PREFIX = "dawnline";

    /** DLQ 접미사 (§4.6). */
    public static final String DLQ_SUFFIX = ".dlq";

    /**
     * {@code eventType} 형식. envelope.v1.schema.json 의 pattern 과 같다.
     *
     * <p>여기가 이 규칙의 <strong>유일한</strong> 출처다. {@link EventEnvelope} 도 이 상수를 쓴다.
     * 규칙이 두 벌이면 쓰기 경로(outbox INSERT)와 읽기 경로(릴레이의 봉투 조립)가 서로 다른 것을
     * 통과시키게 되고, 그러면 INSERT 는 성공하는데 릴레이가 터지는 행 — 즉 아무도 못 지나가는
     * 독약 행 — 이 만들어진다.
     */
    private static final Pattern EVENT_TYPE = Pattern.compile("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$");

    private Topics() {
    }

    /**
     * {@code dawnline.<eventType>.v<major>}.
     *
     * @param eventType     예: {@code order.placed}
     * @param schemaVersion 페이로드 스키마 major 버전 (1 이상)
     */
    public static String forEvent(String eventType, int schemaVersion) {
        requireValidEventType(eventType);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion 은 1 이상이어야 합니다: " + schemaVersion);
        }
        return PREFIX + "." + eventType + ".v" + schemaVersion;
    }

    /**
     * {@code eventType} 이 §4.1·envelope.v1.schema.json 형식인지 검사한다.
     *
     * @param eventType 검사할 이벤트 타입
     * @return 그대로 돌려준 {@code eventType} (검증 후 대입에 쓰라고)
     * @throws IllegalArgumentException 형식이 어긋나면
     */
    public static String requireValidEventType(String eventType) {
        Objects.requireNonNull(eventType, "eventType");
        if (!EVENT_TYPE.matcher(eventType).matches()) {
            throw new IllegalArgumentException(
                    "eventType 은 점으로 구분한 소문자 kebab-case 여야 합니다(예: order.placed): " + eventType);
        }
        return eventType;
    }

    /** 원본 토픽의 DLQ 이름. 이미 DLQ 면 그대로 돌려준다(무한 {@code .dlq.dlq} 방지). */
    public static String dlqFor(String topic) {
        Objects.requireNonNull(topic, "topic");
        return topic.endsWith(DLQ_SUFFIX) ? topic : topic + DLQ_SUFFIX;
    }

    /** DLQ 토픽인가. */
    public static boolean isDlq(String topic) {
        return Objects.requireNonNull(topic, "topic").endsWith(DLQ_SUFFIX);
    }
}
