package com.dawnline.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
}
