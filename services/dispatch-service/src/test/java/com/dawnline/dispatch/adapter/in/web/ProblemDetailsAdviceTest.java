package com.dawnline.dispatch.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.error.CommonErrorCode;
import com.dawnline.common.error.ConflictException;
import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.web.ProblemDetailsAdviceSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 이 서비스만의 답 — {@code retryAfterSeconds()} (ADR-049 결정 3).
 *
 * <p>오류 응답의 <strong>모양</strong>은 {@code libs/web} 의
 * {@code ProblemDetailsAdviceSupportTest} 가 본다. 이 테스트가 뽑기와 함께 처음 생긴 이유는
 * 셋 중 dispatch 에만 어드바이스 테스트가 없었기 때문이다 — 사본 셋이 같다는 것을 아무도
 * 확인하지 않았고, 실제로 확인해 보니 갈라지는 칸은 이 표 하나였다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ProblemDetailsAdvice — dispatch-service 의 Retry-After 표")
class ProblemDetailsAdviceTest {

    private static final String ROUTE_URI = "/api/v1/routes/r";

    private final ProblemDetailsAdvice advice = new ProblemDetailsAdvice();

    @Test
    void 이_서비스의_오류에는_Retry_After_가_붙지_않는다() {
        // 표가 비어 있는 것이 「빠뜨렸다」가 아니라 「그런 오류가 없다」임을 여기서 말한다 —
        // 계획 실행은 wave_id UNIQUE 로 멱등이라 재시도해도 같은 답이 온다 (§5.3).
        assertThat(advice.handleDomain(NotFoundException.of("Route", "r"), request())
                .getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(advice.handleDomain(new ConflictException("이미 계획됨"), request())
                .getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void 예외가_실어_온_대기_시간은_표가_비어_있어도_쓴다() {
        // 빈 표는 「고정값이 없다」이지 「Retry-After 를 쓰지 않는다」가 아니다.
        DomainException exception = new DomainException(CommonErrorCode.UNAVAILABLE, "잠시 후",
                Map.of(ProblemDetailsAdviceSupport.RETRY_AFTER_DETAIL, 3));

        assertThat(advice.handleDomain(exception, request())
                .getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3");
    }

    private static HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", ROUTE_URI);
        request.setRequestURI(ROUTE_URI);
        return request;
    }
}
