package com.dawnline.observability;

/**
 * MDC 에 넣는 id 를 현재 스팬에도 단다 (DESIGN.md §9.3, ADR-062 결정 4) — {@link MdcScope} 만 부른다.
 *
 * <p>현재 스팬은 OpenTelemetry API 의 {@code Span.current()} 다. 서비스의 트레이싱은 Micrometer Tracing 의 OTel 브리지라
 * 관측이 연 스팬이 곧 OTel 컨텍스트의 현재 스팬이다 — 리스너 안이면 소비 스팬, 요청 안이면 서버 스팬. 스팬이 없으면
 * {@code Span.current()} 는 무효 스팬이고 속성 쓰기는 아무것도 하지 않는다.
 *
 * <p>API 가 클래스패스에 없으면(트레이싱이 없는 도구) 건너뛴다. API 를 참조하는 코드는 안쪽 클래스 하나에 있어, 그 클래스가
 * 없는 클래스패스에서는 로드되지 않는다.
 *
 * <p>속성은 스코프를 닫아도 스팬에 남는다 — 스팬 속성은 지울 수 없고, 그 스팬이 그 id 의 일이었다는 것은 닫은 뒤에도 참이다.
 */
final class SpanAttributes {

    private static final boolean OPENTELEMETRY = isPresent("io.opentelemetry.api.trace.Span");

    private SpanAttributes() {
    }

    /**
     * @param mdcKey MDC 키 — {@link MdcKeys#SPAN_ATTRIBUTE_KEYS} 에 없으면 달지 않는다
     * @param value  값
     */
    static void tag(String mdcKey, String value) {
        if (OPENTELEMETRY && MdcKeys.SPAN_ATTRIBUTE_KEYS.contains(mdcKey)) {
            CurrentSpan.tag(MdcKeys.spanAttribute(mdcKey), value);
        }
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, SpanAttributes.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** OpenTelemetry API 를 참조하는 유일한 자리. */
    private static final class CurrentSpan {

        private CurrentSpan() {
        }

        static void tag(String attribute, String value) {
            io.opentelemetry.api.trace.Span.current().setAttribute(attribute, value);
        }
    }
}
