package com.dawnline.messaging.json;

import com.dawnline.messaging.EventEnvelope;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이벤트 봉투·페이로드의 JSON 직렬화 (DESIGN.md §4.2, §4.7).
 *
 * <h2>왜 애플리케이션의 ObjectMapper 를 쓰지 않는가</h2>
 *
 * 이벤트 JSON 은 <strong>서비스 간 계약</strong>이다(contracts/events/*.schema.json).
 * 애플리케이션의 {@code ObjectMapper} 는 {@code spring.jackson.*} 로 언제든 바뀔 수 있고,
 * 누군가 REST 응답의 표기법을 바꾸려고 property naming strategy 를 건드리면
 * 그 순간 Kafka 위의 계약이 조용히 깨진다. 그래서 이 클래스는 <em>전용 매퍼</em>를 만들어 쓴다.
 *
 * <p>Boot 4 의 기본 Jackson 은 <strong>3.x</strong>이고 패키지가 {@code tools.jackson.*} 다.
 * 어노테이션만 {@code com.fasterxml.jackson.annotation.*} 에 그대로 남아 있다.
 *
 * <p>매퍼 설정은 세 가지뿐이다.
 * <ul>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES=false} — §4.7 "소비자는 알 수 없는 필드를 무시해야 한다".
 *       이게 없으면 발행자가 필드를 하나 추가하는 순간 구버전 소비자가 전부 DLQ 로 간다.</li>
 *   <li>{@code WRITE_DATES_AS_TIMESTAMPS=false} — {@code Instant} 를 RFC 3339 문자열로 쓴다.
 *       계약의 {@code "format": "date-time"} 이 요구하는 형태다. Jackson 3 의 기본값이지만
 *       명시해 둔다. 기본값에 기대는 계약은 계약이 아니다.</li>
 *   <li>{@code NON_NULL} — 봉투의 {@code traceId} 는 없을 수 있다. {@code "traceId": null} 로
 *       내보내면 스키마({@code type: string})가 거부한다. 필드를 아예 빼야 한다.</li>
 * </ul>
 */
public final class EventJson {

    private final ObjectMapper mapper;

    /**
     * @param mapper 이벤트 전용 매퍼. 보통 {@link #standard()} 가 만든 것을 쓴다.
     */
    public EventJson(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** 계약이 요구하는 설정만 적용한 전용 매퍼로 만든다. */
    public static EventJson standard() {
        return new EventJson(standardMapper());
    }

    /** 이벤트 전용 {@link JsonMapper}. 테스트·도구가 같은 설정을 재사용할 수 있게 공개한다. */
    public static JsonMapper standardMapper() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .changeDefaultPropertyInclusion(value -> value.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
    }

    /** 임의의 값을 JSON 문자열로. 실패 시 {@code tools.jackson.core.JacksonException}(unchecked). */
    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    /** JSON 문자열을 트리로. */
    public JsonNode readTree(String json) {
        return mapper.readTree(json);
    }

    /** 값을 트리로. outbox 에 넣을 페이로드가 이미 record 일 때 쓴다. */
    public JsonNode toTree(Object value) {
        return mapper.valueToTree(value);
    }

    /**
     * 봉투를 payload 타입까지 고정해 역직렬화한다.
     *
     * @param json        Kafka 레코드 value 전체
     * @param payloadType 페이로드 record 타입
     * @param <T>         페이로드 타입
     */
    public <T> EventEnvelope<T> readEnvelope(String json, Class<T> payloadType) {
        JavaType type = mapper.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
        return mapper.readValue(json, type);
    }

    /**
     * 봉투만 열고 payload 는 트리로 남겨 둔다.
     *
     * <p>릴레이와 "먼저 eventType 을 보고 분기하는" 소비자가 쓰는 형태다.
     */
    public EventEnvelope<JsonNode> readEnvelope(String json) {
        return readEnvelope(json, JsonNode.class);
    }

    /** 트리 페이로드를 도메인 record 로 좁힌다. */
    public <T> T convertPayload(JsonNode payload, Class<T> type) {
        return mapper.convertValue(payload, type);
    }

    /** 이 코덱이 감싼 매퍼. 계약 검증 유틸처럼 같은 설정이 필요한 곳에서 쓴다. */
    public ObjectMapper mapper() {
        return mapper;
    }
}
