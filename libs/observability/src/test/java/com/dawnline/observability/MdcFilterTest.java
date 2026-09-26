package com.dawnline.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link MdcFilter} 의 계약:
 * (1) 요청 처리 중에는 {@code service} 가 MDC 에 있어야 하고,
 * (2) 어떤 경로로 빠져나가든 애플리케이션 소유 MDC 키가 남아 있으면 안 된다.
 *
 * <p>(2)를 어기면 Tomcat 스레드 풀이 스레드를 재사용할 때 다음 요청 로그에 남의
 * {@code orderId} 가 붙는다(DESIGN.md §9.3).
 */
class MdcFilterTest {

    private final MdcFilter filter = new MdcFilter("order-service");

    @BeforeEach
    @AfterEach
    void MDC를비운다() {
        MDC.clear();
    }

    @Test
    void doFilter_체인실행중에는service가MDC에있다() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (request, response) -> seen.set(MDC.get(MdcKeys.SERVICE));

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isEqualTo("order-service");
    }

    @Test
    void doFilter_정상종료후_관리대상MDC가모두비워진다() throws Exception {
        FilterChain chain = (request, response) -> {
            MDC.put(MdcKeys.ORDER_ID, "order-1");
            MDC.put(MdcKeys.WAVE_ID, "wave-1");
        };

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(MdcKeys.MANAGED).allSatisfy(key -> assertThat(MDC.get(key)).isNull());
    }

    @Test
    void doFilter_체인이예외를던져도_관리대상MDC가모두비워진다() {
        FilterChain chain = (request, response) -> {
            MDC.put(MdcKeys.ROUTE_ID, "route-1");
            throw new IllegalStateException("컨트롤러 폭발");
        };

        assertThatThrownBy(() ->
                filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MdcKeys.MANAGED).allSatisfy(key -> assertThat(MDC.get(key)).isNull());
    }

    @Test
    void doFilter_traceId와spanId는지우지않는다() throws Exception {
        MDC.put(MdcKeys.TRACE_ID, "0af7651916cd43dd8448eb211c80319c");
        MDC.put(MdcKeys.SPAN_ID, "b7ad6b7169203331");
        FilterChain chain = (request, response) -> { };

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        // 이 둘은 Micrometer Tracing 이 스팬 스코프에 맞춰 관리한다.
        assertThat(MDC.get(MdcKeys.TRACE_ID)).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(MDC.get(MdcKeys.SPAN_ID)).isEqualTo("b7ad6b7169203331");
    }

    @Test
    void doFilter_개인정보는MDC에넣지않는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.addHeader("X-Customer-Phone", "010-0000-0000");
        request.addParameter("recipient", "홍길동");

        AtomicReference<Map<String, String>> snapshot = new AtomicReference<>();
        FilterChain chain = (req, res) -> snapshot.set(MDC.getCopyOfContextMap());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // 필터가 넣는 것은 service 하나뿐이다. 헤더·파라미터·URL 을 긁어오지 않는다(§9.3, §10).
        assertThat(snapshot.get()).containsOnlyKeys(MdcKeys.SERVICE);
    }

    @Test
    void doFilter_감사_id_헤더는_요청_동안_MDC_에_있고_끝나면_지워진다() throws Exception {
        String auditId = "0199a000-0000-7000-8000-00000000a0d1";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders/x/cancel");
        request.addHeader(MdcKeys.AUDIT_ID_HEADER, auditId);
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(MDC.get(MdcKeys.AUDIT_ID)));

        assertThat(seen.get()).isEqualTo(auditId);
        assertThat(MDC.get(MdcKeys.AUDIT_ID)).as("다음 요청에 남의 감사 id 가 붙지 않는다").isNull();
    }

    @Test
    void doFilter_감사_id_헤더가_UUID_의_정규_형식이_아니면_버린다() throws Exception {
        for (String forged : new String[] {"홍길동 010-0000-0000", "1-1-1-1-1", "", "0199a000-0000-7000-8000-00000000a0d1\nFAKE"}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(MdcKeys.AUDIT_ID_HEADER, forged);
            AtomicReference<Map<String, String>> snapshot = new AtomicReference<>();

            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> snapshot.set(MDC.getCopyOfContextMap()));

            assertThat(snapshot.get()).as("[%s]", forged).containsOnlyKeys(MdcKeys.SERVICE);
        }
    }

    // --- 수신 줄 (ADR-065 결정 4) ------------------------------------------------------------------------------

    @Test
    void doFilter_감사_id_헤더가_오면_처리_전에_수신_줄_하나를_auditId_와_함께_남긴다() throws Exception {
        String auditId = "0199a000-0000-7000-8000-00000000a0d2";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/waves/0199a000-0000-7000-8000-0000000000aa/close");
        request.setQueryString("reason=secret-address");
        request.addHeader(MdcKeys.AUDIT_ID_HEADER, auditId);
        ListAppender<ILoggingEvent> logs = attach();
        AtomicReference<Integer> linesWhenHandled = new AtomicReference<>();
        try {
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> linesWhenHandled.set(logs.list.size()));
        } finally {
            detach(logs);
        }

        assertThat(linesWhenHandled.get()).as("처리 전에 남긴다 — 처리 중에 죽어도 닿았다는 사실은 남는다").isOne();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("운영자 커맨드를 받았습니다: POST /api/v1/waves/0199a000-0000-7000-8000-0000000000aa/close")
                    .as("쿼리 스트링은 싣지 않는다(§9.3 개인정보)").doesNotContain("secret");
            assertThat(event.getMDCPropertyMap()).containsEntry(MdcKeys.AUDIT_ID, auditId);
        });
    }

    @Test
    void doFilter_감사_id_헤더가_없거나_형식이_아니면_수신_줄이_없다() throws Exception {
        ListAppender<ILoggingEvent> logs = attach();
        try {
            filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/orders"), new MockHttpServletResponse(), (req, res) -> { });
            MockHttpServletRequest forged = new MockHttpServletRequest("POST", "/api/v1/orders/x/cancel");
            forged.addHeader(MdcKeys.AUDIT_ID_HEADER, "1-1-1-1-1");
            filter.doFilter(forged, new MockHttpServletResponse(), (req, res) -> { });
        } finally {
            detach(logs);
        }

        assertThat(logs.list).as("운영자 커맨드가 아니다 — 모든 요청에 줄을 남기면 접근 로그가 된다").isEmpty();
    }

    private static ListAppender<ILoggingEvent> attach() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(MdcFilter.class)).addAppender(logs);
        return logs;
    }

    private static void detach(ListAppender<ILoggingEvent> logs) {
        ((Logger) LoggerFactory.getLogger(MdcFilter.class)).detachAppender(logs);
    }
}
