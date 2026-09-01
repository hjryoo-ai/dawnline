package com.dawnline.messaging.kafka;

import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.json.EventJson;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Kafka 레코드를 봉투로 여는 진입점.
 *
 * <p>존재 이유는 §4.6 의 분류를 <strong>한 곳에서</strong> 확정하기 위해서다.
 * JSON 파싱 실패는 {@link JacksonException}, 봉투 불변식 위반(UUIDv7 아님, eventType 형식 오류 등)은
 * {@link IllegalArgumentException} 으로 나오는데, 둘 다 §4.6 의 "역직렬화 실패/스키마 불일치 → 즉시 DLQ" 다.
 * 그런데 {@code IllegalArgumentException} 을 통째로 재시도 제외 목록에 넣으면 리스너 코드의 평범한
 * 인자 검증 실수까지 조용히 DLQ 로 가 버린다. 그래서 <em>파싱 경계에서만</em>
 * {@link NonRetryableEventException} 으로 감싼다.
 */
public final class EventRecords {

    private EventRecords() {
    }

    /**
     * 레코드 value 를 봉투로 연다. payload 는 트리로 남긴다.
     *
     * @param json   이벤트 전용 JSON 코덱
     * @param record 받은 레코드
     * @return 봉투
     * @throws NonRetryableEventException JSON 이 깨졌거나 봉투 불변식을 어겼을 때 (즉시 DLQ)
     */
    public static EventEnvelope<JsonNode> parse(EventJson json, ConsumerRecord<String, String> record) {
        return parse(json, record, JsonNode.class);
    }

    /**
     * 레코드 value 를 payload 타입까지 고정해 연다.
     *
     * @param json        이벤트 전용 JSON 코덱
     * @param record      받은 레코드
     * @param payloadType 페이로드 record 타입
     * @param <T>         페이로드 타입
     * @return 봉투
     * @throws NonRetryableEventException JSON 이 깨졌거나 봉투 불변식을 어겼을 때 (즉시 DLQ)
     */
    public static <T> EventEnvelope<T> parse(EventJson json, ConsumerRecord<String, String> record,
            Class<T> payloadType) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(payloadType, "payloadType");

        String value = record.value();
        if (value == null) {
            throw new NonRetryableEventException(
                    "레코드 value 가 비어 있습니다: topic=%s, partition=%d, offset=%d"
                            .formatted(record.topic(), record.partition(), record.offset()));
        }
        try {
            return json.readEnvelope(value, payloadType);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new NonRetryableEventException(
                    "이벤트 봉투를 열 수 없습니다: topic=%s, partition=%d, offset=%d"
                            .formatted(record.topic(), record.partition(), record.offset()), e);
        }
    }
}
