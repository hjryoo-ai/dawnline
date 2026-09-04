package com.dawnline.order.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.error.CommonErrorCode;
import com.dawnline.common.error.ConflictException;
import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.common.error.ValidationException;
import com.dawnline.order.domain.OrderErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * 오류 응답의 모양 (RFC 9457, CLAUDE.md 「코딩 컨벤션」).
 *
 * <p>{@code OrderApiIT} 가 실물 요청으로 보는 것과 별개로, 어드바이스 자체는 서블릿 컨테이너 없이
 * 검증할 수 있다. 특히 {@code type} 이 {@code null} 인 프레임워크 ProblemDetail 을 다루는 경로는
 * 여기서 훨씬 싸게 잡힌다 — 실제로 그 널을 빠뜨려 "본문 없는 400" 이 나갔었다.
 */
@DisplayName("ProblemDetailsAdvice — RFC 9457 오류 응답")
class ProblemDetailsAdviceTest {

    private static final String TYPE_PREFIX = "https://dawnline.internal/problems/";

    private final ProblemDetailsAdvice advice = new ProblemDetailsAdvice();

    private static HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.setRequestURI("/api/v1/orders");
        return request;
    }

    @Test
    void 도메인_예외는_type_과_code_를_채운다() {
        // type 이 about:blank 면 클라이언트가 status 와 사람이 읽는 문장으로 분기해야 한다.
        ResponseEntity<ProblemDetail> response =
                advice.handleDomain(new NotFoundException("없습니다"), request());

        ProblemDetail problem = response.getBody();
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(problem).isNotNull();
        assertThat(problem.getType()).isEqualTo(URI.create(TYPE_PREFIX + "not-found"));
        assertThat(problem.getProperties()).containsEntry("code", "not-found");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/api/v1/orders"));
        assertThat(problem.getDetail()).isEqualTo("없습니다");
    }

    @Test
    void 예외의_상세가_확장_멤버로_나간다() {
        DomainException exception = new DomainException(CommonErrorCode.UNPROCESSABLE_REQUEST,
                "안 됩니다", Map.of("serviceTier", "DAWN", "eligibleTiers", "NEXT_DAY"));

        ProblemDetail problem = advice.handleDomain(exception, request()).getBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getProperties())
                .containsEntry("serviceTier", "DAWN")
                .containsEntry("eligibleTiers", "NEXT_DAY");
    }

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
        // 이미 완료된 멱등 키, 취소 불가 상태 — 다시 보내도 결과가 같다.
        ResponseEntity<ProblemDetail> response =
                advice.handleDomain(new ConflictException("이미 완료"), request());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void 프레임워크가_만든_type_이_null_인_ProblemDetail_도_분류한다() {
        // Spring 7 의 ProblemDetail 은 type 이 about:blank 가 아니라 null 이다. 널 비교를 빠뜨리면
        // 예외 처리기 안에서 NPE 가 나고 응답이 본문 없는 400 으로 조용히 나간다.
        ProblemDetail raw = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), "본문을 읽을 수 없습니다");
        assertThat(raw.getType()).as("전제: 프레임워크는 type 을 비워 둔다").isNull();

        ResponseEntity<Object> response = advice.createResponseEntity(raw, new HttpHeaders(),
                HttpStatusCode.valueOf(400), new ServletWebRequest(new MockHttpServletRequest("GET", "/api/v1/orders")));

        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getType()).isEqualTo(URI.create(TYPE_PREFIX + "validation-failed"));
        assertThat(problem.getProperties()).containsEntry("code", "validation-failed");
    }

    @Test
    void 상태_코드에_맞는_코드를_고른다() {
        assertThat(classify(404).getProperties()).containsEntry("code", "not-found");
        assertThat(classify(409).getProperties()).containsEntry("code", "conflict");
        assertThat(classify(415).getProperties()).containsEntry("code", "validation-failed");
        assertThat(classify(503).getProperties()).containsEntry("code", "unavailable");
    }

    @Test
    void 이미_분류된_ProblemDetail_은_덮어쓰지_않는다() {
        ProblemDetail ours = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), "이미 우리 것");
        ours.setType(URI.create(TYPE_PREFIX + "unprocessable-request"));
        ours.setProperty("code", "unprocessable-request");

        advice.createResponseEntity(ours, new HttpHeaders(), HttpStatusCode.valueOf(422),
                new ServletWebRequest(new MockHttpServletRequest("GET", "/api/v1/orders")));

        assertThat(ours.getType()).isEqualTo(URI.create(TYPE_PREFIX + "unprocessable-request"));
    }

    @Test
    void 예상하지_못한_예외는_내부_정보를_흘리지_않는다() {
        ResponseEntity<ProblemDetail> response = advice.handleUnexpected(
                new IllegalStateException("SELECT * FROM orders WHERE secret = 'x'"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getDetail()).isEqualTo("요청을 처리하지 못했습니다");
        assertThat(problem.toString()).doesNotContain("SELECT").doesNotContain("secret");
    }

    @Test
    void 검증_예외도_같은_모양으로_나간다() {
        ProblemDetail problem = advice
                .handleDomain(ValidationException.field("postalCode", "062", "5자리여야 합니다"), request())
                .getBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getProperties()).containsEntry("field", "postalCode");
    }

    private ProblemDetail classify(int status) {
        ProblemDetail raw = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), "무엇인가");
        advice.createResponseEntity(raw, new HttpHeaders(), HttpStatusCode.valueOf(status),
                new ServletWebRequest(new MockHttpServletRequest("GET", "/api/v1/orders")));
        return raw;
    }
}
