package com.dawnline.tracking.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.error.CommonErrorCode;
import com.dawnline.common.error.ConflictException;
import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.IllegalStateTransitionException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.tracking.domain.ShipmentStatus;
import com.dawnline.web.ProblemDetailsAdviceSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 이 서비스만의 답 — {@code retryAfterSeconds()} (ADR-049 결정 3).
 *
 * <p>오류 응답의 <strong>모양</strong>은 {@code libs/web} 의
 * {@code ProblemDetailsAdviceSupportTest} 가 본다. 여기 남는 것은 그 훅 하나와, 이 서비스의
 * 도메인 예외가 그 모양에 실제로 실리는지다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ProblemDetailsAdvice — tracking-service 의 Retry-After 표")
class ProblemDetailsAdviceTest {

    private static final String TYPE_PREFIX = ProblemDetailsAdviceSupport.PROBLEM_TYPE_PREFIX;

    private static final String SCAN_URI = "/api/v1/routes/r/stops/1/events";

    private final ProblemDetailsAdvice advice = new ProblemDetailsAdvice();

    @Test
    void 이_서비스의_오류에는_Retry_After_가_붙지_않는다() {
        // 표가 비어 있는 것이 「빠뜨렸다」가 아니라 「그런 오류가 없다」임을 여기서 말한다.
        // 404 는 재시도가 통하지만 <언제>인지를 우리가 모른다(브로커 랙에 달려 있다) —
        // 모르는 값을 적어 두면 그 숫자가 계약이 된다.
        assertThat(advice.handleDomain(NotFoundException.of("Stop", "r/1"), request())
                .getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(advice.handleDomain(new ConflictException("충돌"), request())
                .getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void 예외가_실어_온_대기_시간은_표가_비어_있어도_쓴다() {
        // 빈 표는 「고정값이 없다」이지 「Retry-After 를 쓰지 않는다」가 아니다.
        DomainException exception = new DomainException(CommonErrorCode.UNAVAILABLE, "잠시 후",
                Map.of(ProblemDetailsAdviceSupport.RETRY_AFTER_DETAIL, 5));

        assertThat(advice.handleDomain(exception, request())
                .getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }

    @Test
    void 잘못된_상태_전이는_409_다() {
        // 여기까지 오는 것은 상류의 결함이다 — 역행 스캔은 200 + STALE 이다 (§5.4 축 규칙).
        ProblemDetail problem = advice.handleDomain(
                new IllegalStateTransitionException("Shipment",
                        ShipmentStatus.COMPLETED, ShipmentStatus.ARRIVED), request()).getBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getType()).isEqualTo(URI.create(TYPE_PREFIX + "illegal-state-transition"));
    }

    private static HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", SCAN_URI);
        request.setRequestURI(SCAN_URI);
        return request;
    }
}
