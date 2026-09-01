package com.dawnline.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** W3C Trace Context 헤더 파싱 (DESIGN.md §4.2, §9.2). */
class EventHeadersTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    @Test
    void traceIdFrom_정상_traceparent에서_traceId를_뽑는다() {
        assertThat(EventHeaders.traceIdFrom(TRACEPARENT)).contains(TRACE_ID);
    }

    @Test
    void traceIdFrom_null이면_비어있다() {
        assertThat(EventHeaders.traceIdFrom(null)).isEmpty();
    }

    @Test
    void traceIdFrom_형식이_틀리면_비어있다() {
        // 관측용 헤더가 깨졌다고 이벤트를 DLQ 로 보내지 않는다 (contracts/events/README §4.2).
        assertThat(EventHeaders.traceIdFrom("garbage")).isEmpty();
        assertThat(EventHeaders.traceIdFrom("00-tooshort-00f067aa0ba902b7-01")).isEmpty();
        assertThat(EventHeaders.traceIdFrom("00-" + TRACE_ID.toUpperCase(java.util.Locale.ROOT)
                + "-00f067aa0ba902b7-01")).isEmpty();
    }

    @Test
    void traceIdFrom_traceId가_전부_0이면_비어있다() {
        // W3C 사양상 all-zero trace-id 는 무효다.
        assertThat(EventHeaders.traceIdFrom("00-" + "0".repeat(32) + "-00f067aa0ba902b7-01")).isEmpty();
    }

    @Test
    void isValidTraceparent_판별한다() {
        assertThat(EventHeaders.isValidTraceparent(TRACEPARENT)).isTrue();
        assertThat(EventHeaders.isValidTraceparent("00-x-y-01")).isFalse();
    }

    @Test
    void toBytes_UTF8로_변환한다() {
        assertThat(EventHeaders.toBytes("order.placed")).isEqualTo("order.placed".getBytes(StandardCharsets.UTF_8));
    }
}
