package com.dawnline.order.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.error.ConflictException;
import com.dawnline.common.error.DomainException;
import com.dawnline.order.domain.OrderErrorCode;
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
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 이 서비스만의 답 — {@code retryAfterSeconds()} (ADR-049 결정 3).
 *
 * <p>오류 응답의 <strong>모양</strong>은 {@code libs/web} 의
 * {@code ProblemDetailsAdviceSupportTest} 가 본다. 여기 남는 것은 그 훅 하나이고,
 * {@code OrderApiIT} 가 실물 요청으로 다시 한 번 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ProblemDetailsAdvice — order-service 의 Retry-After 표")
class ProblemDetailsAdviceTest {

    private static final String TYPE_PREFIX = ProblemDetailsAdviceSupport.PROBLEM_TYPE_PREFIX;

    private final ProblemDetailsAdvice advice = new ProblemDetailsAdvice();

    @Test
    void 처리_중인_멱등_키에는_Retry_After_가_붙는다() {
        // 같은 409 라도 "잠시 후 그대로 재시도하면 되는 것" 은 이것뿐이다. 응답 자체가 그 계약을 말한다.
        DomainException inFlight = new DomainException(OrderErrorCode.IDEMPOTENT_REQUEST_IN_FLIGHT, "처리 중");

        ResponseEntity<ProblemDetail> response = advice.handleDomain(inFlight, request());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getType())
                .isEqualTo(URI.create(TYPE_PREFIX + "idempotent-request-in-flight"));
    }

    @Test
    void 재시도해도_소용없는_409_에는_Retry_After_가_없다() {
        // 이미 완료된 멱등 키, 취소 불가 상태 — 다시 보내도 결과가 같다. 표가 코드 하나만
        // 담고 있다는 것을 「전부에 붙지는 않는다」쪽에서도 말한다.
        ResponseEntity<ProblemDetail> response =
                advice.handleDomain(new ConflictException("이미 완료"), request());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void 레이트_리밋은_표가_아니라_예외가_대기_시간을_싣는다() {
        // 얼마나 기다려야 하는지가 토큰 버킷의 상태에 달려 있어 고정값을 적을 수 없다
        // (OrderController 가 RateLimiter.Decision 에서 꺼내 싣는다).
        DomainException limited = new DomainException(OrderErrorCode.RATE_LIMITED, "요청이 너무 잦습니다",
                Map.of(ProblemDetailsAdviceSupport.RETRY_AFTER_DETAIL, 7));

        assertThat(advice.handleDomain(limited, request())
                .getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("7");
    }

    private static HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.setRequestURI("/api/v1/orders");
        return request;
    }
}
