package com.dawnline.observability;

import java.util.List;

/**
 * 구조화 로그의 MDC 키 (DESIGN.md §9.3).
 *
 * <p>§9.3 이 요구하는 필드는 {@code traceId, spanId, service, eventId, orderId/waveId/routeId} 다.
 * 이 중 {@link #TRACE_ID}, {@link #SPAN_ID} 는 <strong>애플리케이션이 넣지 않는다</strong>.
 * Spring Boot 4.1 이 자동 구성하는 {@code io.micrometer.tracing.otel.bridge.Slf4JEventListener}
 * 빈이 스코프 열림/닫힘 이벤트에 맞춰 직접 {@code MDC.put("traceId"|"spanId", ...)} 를 한다
 * (Boot 4.1 의 {@code OpenTelemetryTracingAutoConfiguration#otelSlf4JEventListener} 에서 확인).
 * 우리가 같은 키를 덮어쓰면 스팬 경계와 로그가 어긋나므로 상수만 두고 값은 건드리지 않는다.
 *
 * <h2>개인정보 금지 (CLAUDE.md 로그 규칙, DESIGN.md §9.3 · §10)</h2>
 * <p><strong>전체 주소, 수령인 이름, 전화번호, 이메일 같은 고객 식별 정보를 MDC 나 로그
 * 메시지에 절대 넣지 않는다.</strong> 위치를 남겨야 하면 우편번호 또는 geohash(권역 5자리,
 * stop 7자리)만 남긴다. 이 정책이 MDC 에서 특히 중요한 이유는, MDC 값이 구조화 로그의
 * <em>모든</em> 줄에 자동으로 복사되어 로그 저장소 전체로 퍼지기 때문이다.
 * 새 키를 {@link #MANAGED} 에 추가하기 전에 그 값이 개인을 식별할 수 있는지 먼저 따진다.
 *
 * <p>키 이름은 카멜케이스다. Boot 4.1 의 구조화 로깅({@code logging.structured.format.console})
 * 은 MDC 맵을 그대로 JSON 최상위(logstash 포맷) 또는 중첩(ecs 포맷) 멤버로 옮기므로,
 * 여기 적힌 이름이 곧 로그 JSON 의 필드 이름이 된다.
 */
public final class MdcKeys {

    private MdcKeys() {
        throw new AssertionError("상수 홀더입니다. 인스턴스를 만들지 마세요.");
    }

    /**
     * W3C trace id (32 hex). <strong>Micrometer Tracing 이 자동으로 넣고 뺀다.</strong>
     * 애플리케이션 코드에서 put/remove 하지 않는다.
     */
    public static final String TRACE_ID = "traceId";

    /**
     * W3C span id (16 hex). <strong>Micrometer Tracing 이 자동으로 넣고 뺀다.</strong>
     * 애플리케이션 코드에서 put/remove 하지 않는다.
     */
    public static final String SPAN_ID = "spanId";

    /**
     * 서비스 이름. HTTP 요청은 {@link MdcFilter} 가, 이벤트 처리는 {@link MdcScope} 가 넣는다.
     *
     * <p><strong>주의</strong>: 이 키 때문에 구조화 로그 포맷으로 {@code ecs} 를 쓸 수 없다.
     * ECS 포맷은 JSON 에 {@code service.name} 등 {@code service} 객체를 직접 쓰기 때문에
     * MDC 의 {@code service} 와 이름이 충돌하고, Boot 의 JsonWriter 가
     * {@code IllegalStateException: The name 'service' has already been written} 을 던져
     * <em>그 로그 줄이 통째로 사라진다</em>(설정은 성공하므로 조용히 유실된다 — 실측 확인).
     * 그래서 기본 포맷은 {@code logstash} 다({@code observability-defaults.yml}).
     */
    public static final String SERVICE = "service";

    /** 이벤트 봉투의 {@code eventId}(UUIDv7). 멱등 소비 추적의 기준 키다(§4.2, §4.4). */
    public static final String EVENT_ID = "eventId";

    /** 주문 ID(UUIDv7). */
    public static final String ORDER_ID = "orderId";

    /** 웨이브 ID(UUIDv7). */
    public static final String WAVE_ID = "waveId";

    /** 라우트 ID(UUIDv7). */
    public static final String ROUTE_ID = "routeId";

    /**
     * 애플리케이션이 관리하는 키 목록 — 즉 {@link MdcScope} 가 넣고, {@link MdcFilter} 가
     * 요청 종료 시 지우는 대상이다. {@link #TRACE_ID}/{@link #SPAN_ID} 는 소유자가
     * Micrometer Tracing 이므로 <strong>일부러 제외</strong>했다.
     */
    public static final List<String> MANAGED = List.of(
            SERVICE, EVENT_ID, ORDER_ID, WAVE_ID, ROUTE_ID);
}
