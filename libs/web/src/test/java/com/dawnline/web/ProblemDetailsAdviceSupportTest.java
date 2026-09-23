package com.dawnline.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.error.CommonErrorCode;
import com.dawnline.common.error.ConflictException;
import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.common.error.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.FieldError;
import org.springframework.validation.MapBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * 오류 응답의 모양 (RFC 9457, CLAUDE.md 「코딩 컨벤션」, ADR-049).
 *
 * <p>여기 있는 것은 <strong>서비스 셋에 공통인 부분</strong>이다. 서비스 쪽 테스트에는 그
 * 서비스만의 답({@code retryAfterSeconds()})을 보는 것만 남는다.
 *
 * <p>특히 {@code type} 이 {@code null} 인 프레임워크 ProblemDetail 을 다루는 경로는 서블릿
 * 컨테이너 없이 여기서 훨씬 싸게 잡힌다 — 실제로 그 널을 빠뜨려 「본문 없는 400」이 나갔었다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ProblemDetailsAdviceSupport — RFC 9457 오류 응답")
class ProblemDetailsAdviceSupportTest {

    private static final String TYPE_PREFIX = ProblemDetailsAdviceSupport.PROBLEM_TYPE_PREFIX;

    private static final String URI_PATH = "/api/v1/things";

    /** 표가 빈 서비스. */
    private final ProblemDetailsAdviceSupport advice = new TestAdvice(Map.of());

    /** 표에 한 줄이 있는 서비스 (order-service 의 멱등 키가 그 모양이다). */
    private final ProblemDetailsAdviceSupport withTable = new TestAdvice(Map.of("conflict", 1));

    @Test
    void 도메인_예외는_type_과_code_를_채운다() {
        // type 이 about:blank 면 클라이언트가 status 와 사람이 읽는 문장으로 분기해야 하고,
        // 문장은 언제든 바뀐다.
        ResponseEntity<ProblemDetail> response =
                advice.handleDomain(new NotFoundException("없습니다"), request());

        ProblemDetail problem = response.getBody();
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(problem).isNotNull();
        assertThat(problem.getType()).isEqualTo(URI.create(TYPE_PREFIX + "not-found"));
        assertThat(problem.getProperties()).containsEntry("code", "not-found");
        assertThat(problem.getInstance()).isEqualTo(URI.create(URI_PATH));
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
    void 검증_예외도_같은_모양으로_나간다() {
        ProblemDetail problem = advice
                .handleDomain(ValidationException.field("postalCode", "062", "5자리여야 합니다"), request())
                .getBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getProperties()).containsEntry("field", "postalCode");
    }

    @Test
    void 표에_있는_코드에는_Retry_After_가_붙는다() {
        // 같은 409 라도 「잠시 후 그대로 재시도하면 되는 것」이 있다. 응답 자체가 그 계약을 말한다.
        ResponseEntity<ProblemDetail> response =
                withTable.handleDomain(new ConflictException("처리 중"), request());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    void 표에_없는_코드에는_Retry_After_가_없다() {
        // 이미 완료된 멱등 키, 취소 불가 상태 — 다시 보내도 결과가 같다.
        assertThat(advice.handleDomain(new ConflictException("이미 완료"), request())
                .getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void 예외가_실어_온_대기_시간이_표보다_우선한다() {
        // 레이트 리밋처럼 <얼마나> 기다려야 하는지가 상황마다 다른 경우다.
        DomainException exception = new DomainException(CommonErrorCode.CONFLICT, "잠시 후",
                Map.of(ProblemDetailsAdviceSupport.RETRY_AFTER_DETAIL, 5));

        assertThat(withTable.handleDomain(exception, request())
                .getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .as("표에는 conflict=1 이 있지만 예외가 실어 온 5 가 이긴다")
                .isEqualTo("5");
    }

    @Test
    void 실어_온_대기_시간이_0_이하여도_최소_1초로_말한다() {
        // 0 을 그대로 내보내면 「지금 바로 다시 보내라」가 되어 같은 경합을 되풀이하게 한다.
        DomainException exception = new DomainException(CommonErrorCode.CONFLICT, "잠시 후",
                Map.of(ProblemDetailsAdviceSupport.RETRY_AFTER_DETAIL, 0));

        assertThat(advice.handleDomain(exception, request())
                .getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    void 프레임워크가_만든_type_이_null_인_ProblemDetail_도_분류한다() {
        // Spring 7 의 ProblemDetail 은 type 이 about:blank 가 아니라 null 이다. 널 비교를 빠뜨리면
        // 예외 처리기 안에서 NPE 가 나고 응답이 본문 없는 400 으로 조용히 나간다.
        ProblemDetail raw = ProblemDetail.forStatusAndDetail(
                HttpStatusCode.valueOf(400), "본문을 읽을 수 없습니다");
        assertThat(raw.getType()).as("전제: 프레임워크는 type 을 비워 둔다").isNull();

        ResponseEntity<Object> response = advice.createResponseEntity(
                raw, new HttpHeaders(), HttpStatusCode.valueOf(400), webRequest());

        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getType()).isEqualTo(URI.create(TYPE_PREFIX + "validation-failed"));
        assertThat(problem.getProperties()).containsEntry("code", "validation-failed");
        assertThat(problem.getInstance()).isEqualTo(URI.create(URI_PATH));
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

        advice.createResponseEntity(ours, new HttpHeaders(), HttpStatusCode.valueOf(422), webRequest());

        assertThat(ours.getType()).isEqualTo(URI.create(TYPE_PREFIX + "unprocessable-request"));
    }

    @Test
    void 어긴_필드를_전부_싣는다() throws Exception {
        // 하나씩 고치며 다시 보내게 하면 왕복이 필드 수만큼 늘어난다.
        MapBindingResult binding = new MapBindingResult(new HashMap<>(), "request");
        binding.addError(new FieldError("request", "postalCode", "5자리여야 합니다"));
        binding.addError(new FieldError("request", "items", null));
        MethodParameter parameter = new MethodParameter(
                ProblemDetailsAdviceSupportTest.class.getDeclaredMethod("bindingTarget", String.class), 0);

        ResponseEntity<Object> response = advice.handleMethodArgumentNotValid(
                new MethodArgumentNotValidException(parameter, binding),
                new HttpHeaders(), HttpStatusCode.valueOf(400), webRequest());

        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> errors =
                (List<Map<String, String>>) problem.getProperties().get("errors");
        assertThat(errors).containsExactly(
                Map.of("field", "postalCode", "reason", "5자리여야 합니다"),
                // 메시지가 없어도 자리는 남긴다 — 필드 이름만으로도 어디가 틀렸는지는 말할 수 있다.
                Map.of("field", "items", "reason", "유효하지 않습니다"));
    }

    @Test
    void 거부된_값은_오류_본문에_담기지_않는다() throws Exception {
        // 주소·연락처가 그대로 오류 응답과 로그에 실린다 (§9.3). FieldError 는 rejectedValue 를
        // 들고 있지만 우리가 꺼내지 않는다.
        MapBindingResult binding = new MapBindingResult(new HashMap<>(), "request");
        binding.addError(new FieldError("request", "address", "서울시 강남구 테헤란로 123",
                false, null, null, "형식이 올바르지 않습니다"));
        MethodParameter parameter = new MethodParameter(
                ProblemDetailsAdviceSupportTest.class.getDeclaredMethod("bindingTarget", String.class), 0);

        ResponseEntity<Object> response = advice.handleMethodArgumentNotValid(
                new MethodArgumentNotValidException(parameter, binding),
                new HttpHeaders(), HttpStatusCode.valueOf(400), webRequest());

        assertThat(String.valueOf(response.getBody())).doesNotContain("테헤란로");
    }

    @Test
    void 예상하지_못한_예외는_내부_정보를_흘리지_않는다() {
        // 스택 트레이스·SQL·클래스 이름은 공격자에게는 지도이고 사용자에게는 아무 의미가 없다.
        ResponseEntity<ProblemDetail> response = advice.handleUnexpected(
                new IllegalStateException("SELECT * FROM orders WHERE secret = 'x'"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getDetail()).isEqualTo("요청을 처리하지 못했습니다");
        assertThat(problem.toString()).doesNotContain("SELECT").doesNotContain("secret");
    }

    private ProblemDetail classify(int status) {
        ProblemDetail raw = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), "무엇인가");
        advice.createResponseEntity(raw, new HttpHeaders(), HttpStatusCode.valueOf(status), webRequest());
        return raw;
    }

    private static HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", URI_PATH);
        request.setRequestURI(URI_PATH);
        return request;
    }

    private static ServletWebRequest webRequest() {
        return new ServletWebRequest(new MockHttpServletRequest("POST", URI_PATH));
    }

    /** {@link MethodParameter} 를 만들기 위한 자리. 호출되지 않는다. */
    @SuppressWarnings("unused")
    private void bindingTarget(String body) {
        // 비어 있다.
    }

    /** 훅 하나만 다른 하위 클래스 — 서비스들이 실제로 하는 것과 같은 모양이다. */
    private static final class TestAdvice extends ProblemDetailsAdviceSupport {

        private final Map<String, Integer> table;

        private TestAdvice(Map<String, Integer> table) {
            this.table = table;
        }

        @Override
        protected Map<String, Integer> retryAfterSeconds() {
            return table;
        }
    }
}
