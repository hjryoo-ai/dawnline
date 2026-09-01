package com.dawnline.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP 요청 동안 MDC 에 서비스 이름을 넣고, 끝나면 애플리케이션이 관리하는 MDC 키를 모두
 * 지우는 서블릿 필터 (DESIGN.md §9.3).
 *
 * <h2>이 필터가 넣는 것</h2>
 * <p>{@link MdcKeys#SERVICE} 하나뿐이다. 나머지는 소유자가 따로 있다.
 * <ul>
 *   <li>{@code traceId}/{@code spanId} — Micrometer Tracing 의 {@code Slf4JEventListener} 가
 *       스팬 스코프에 맞춰 자동으로 넣는다. 여기서 손대면 스팬 경계와 어긋난다.</li>
 *   <li>{@code orderId}/{@code waveId}/{@code routeId}/{@code eventId} — 값을 아는 곳,
 *       즉 유스케이스와 리스너가 {@link MdcScope} 로 넣는다. URL 에서 긁어오지 않는다
 *       (경로 변수 파싱이 컨트롤러와 이중 관리가 되고, 잘못된 값이 로그에 남는다).</li>
 * </ul>
 *
 * <h2>왜 finally 에서 반드시 지우는가</h2>
 * <p>서블릿 컨테이너(Tomcat)는 스레드 풀을 재사용한다. 어떤 요청이 예외로 빠져나가며
 * {@code orderId} 를 MDC 에 남기면, <strong>같은 스레드로 처리되는 다음 요청의 모든 로그 줄에
 * 남의 주문 ID 가 붙는다.</strong> 조사 시 완전히 잘못된 결론으로 이어지므로, 정상·예외
 * 어느 경로로 나가든 {@link MdcScope#clearManaged()} 로 정리한다.
 * 비동기 디스패치에서는 {@link OncePerRequestFilter} 기본값대로 필터가 다시 돌지 않으며,
 * 다른 스레드로 넘어간 작업은 그쪽에서 {@link MdcScope} 로 자기 컨텍스트를 연다.
 *
 * <h2>개인정보</h2>
 * <p>요청 헤더·바디·쿼리 스트링을 MDC 에 넣지 않는다. 주소·수령인·연락처가 그대로 로그
 * 저장소로 흘러가기 때문이다(DESIGN.md §9.3, §10).
 *
 * <p>등록은 {@code com.dawnline.observability.config.ObservabilityAutoConfiguration} 이
 * 자동으로 한다. 직접 등록할 일은 없다.
 */
public final class MdcFilter extends OncePerRequestFilter {

    private final String serviceName;

    /**
     * @param serviceName {@code spring.application.name} 값. 로그의 {@code service} 필드가 된다.
     */
    public MdcFilter(String serviceName) {
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        MDC.put(MdcKeys.SERVICE, serviceName);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MdcScope.clearManaged();
        }
    }
}
