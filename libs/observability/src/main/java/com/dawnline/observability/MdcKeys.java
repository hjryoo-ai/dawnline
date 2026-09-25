package com.dawnline.observability;

import java.util.List;
import java.util.Map;

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
     * ops-api 감사 행의 id(UUIDv7) — 운영자 커맨드가 코어를 부를 때 {@link #AUDIT_ID_HEADER} 로 싣는다
     * (DESIGN.md §5.5 「커맨드 위임」, §9.3). 사람이 결과를 모르는({@code UNKNOWN}) 감사 행을 해소할 때
     * 코어의 로그에서 찾는 키다. ops-api 가 만든 id 라 개인을 식별하지 않는다.
     */
    public static final String AUDIT_ID = "auditId";

    /** {@link #AUDIT_ID} 를 싣는 요청 헤더. ops-api 가 보내고 {@link MdcFilter} 가 읽는다. */
    public static final String AUDIT_ID_HEADER = "X-Dawnline-Audit-Id";

    /**
     * 애플리케이션이 관리하는 키 목록 — 즉 {@link MdcScope} 가 넣고, {@link MdcFilter} 가
     * 요청 종료 시 지우는 대상이다. {@link #TRACE_ID}/{@link #SPAN_ID} 는 소유자가
     * Micrometer Tracing 이므로 <strong>일부러 제외</strong>했다.
     */
    public static final List<String> MANAGED = List.of(
            SERVICE, EVENT_ID, ORDER_ID, WAVE_ID, ROUTE_ID, AUDIT_ID);

    /** 스팬 속성 이름의 접두 — {@link #spanAttribute}. */
    public static final String SPAN_ATTRIBUTE_PREFIX = "dawnline.";

    /**
     * {@link #MANAGED} 중 스팬 속성으로 <strong>달지 않는</strong> 키와 그 이유 (§9.3, ADR-062 결정 4). 스팬에 다는 키는
     * 여기서 빼서 정한다({@link #SPAN_ATTRIBUTE_KEYS}) — 새 MDC 키는 이유를 적어 빼지 않는 한 스팬에도 달린다.
     */
    public static final Map<String, String> NOT_SPAN_ATTRIBUTES = Map.of(
            SERVICE, "스팬에는 리소스 속성 service.name 이 이미 있다 — 같은 값을 두 이름으로 싣지 않는다");

    /** {@link MdcScope} 가 MDC 와 함께 현재 스팬에도 다는 키 — {@link #MANAGED} 에서 {@link #NOT_SPAN_ATTRIBUTES} 를 뺀 것. */
    public static final List<String> SPAN_ATTRIBUTE_KEYS = MANAGED.stream()
            .filter(key -> !NOT_SPAN_ATTRIBUTES.containsKey(key))
            .toList();

    /**
     * MDC 키의 스팬 속성 이름 — {@code dawnline.} + snake_case({@code waveId} → {@code dawnline.wave_id}).
     *
     * <p>두 이름을 따로 적지 않고 규칙으로 낸다. 로그와 트레이스가 같은 id 를 다른 이름으로 부르기 시작하면 「이 로그의
     * 웨이브를 트레이스에서 찾는다」가 번역이 된다. TraceQL 로는 {@code { span.dawnline.wave_id = "…" }}.
     *
     * @param mdcKey MDC 키(카멜케이스)
     * @return 스팬 속성 이름
     */
    public static String spanAttribute(String mdcKey) {
        StringBuilder name = new StringBuilder(SPAN_ATTRIBUTE_PREFIX);
        for (char c : mdcKey.toCharArray()) {
            if (Character.isUpperCase(c)) {
                name.append('_').append(Character.toLowerCase(c));
            } else {
                name.append(c);
            }
        }
        return name.toString();
    }
}
