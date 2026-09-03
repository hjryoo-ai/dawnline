package com.dawnline.order.adapter.in.web;

import com.dawnline.common.error.CommonErrorCode;
import com.dawnline.common.error.DomainException;
import com.dawnline.order.domain.OrderErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 도메인 예외 → RFC 9457 Problem Details (CLAUDE.md 「코딩 컨벤션」).
 *
 * <p>이 서비스의 <strong>유일한</strong> {@code @ControllerAdvice} 다. 오류 응답의 모양이 한 곳에서만
 * 정해져야 클라이언트가 그것을 계약으로 삼을 수 있다.
 *
 * <h2>{@code type} 을 반드시 채운다</h2>
 * RFC 9457 에서 {@code type} 은 "이 오류가 무엇인가" 의 안정적인 식별자다. 비워 두면
 * ({@code about:blank}) 클라이언트가 <em>status 와 사람이 읽는 문장</em>으로 분기해야 하는데,
 * 문장은 언제든 바뀐다. 우리 {@code ErrorCode} 가 이미 안정적인 코드를 갖고 있으므로 그것을
 * URI 로 만든다.
 *
 * <h2>{@code Retry-After}</h2>
 * 같은 409 라도 "잠시 후 그대로 재시도하면 되는 것" 과 "재시도해도 결과가 같은 것" 이 있다.
 * 전자에만 {@code Retry-After} 를 붙여, <strong>응답 자체가 재시도 계약을 말하게</strong> 한다.
 * 그 목록이 {@link #RETRY_AFTER_SECONDS} 이고, 지금은 멱등 키 처리 중(409) 하나뿐이다.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    /** {@code type} URI 의 접두어. 계약 파일의 {@code $id} 와 같은 호스트를 쓴다. */
    private static final String PROBLEM_TYPE_PREFIX = "https://dawnline.internal/problems/";

    /**
     * 잠시 후 <em>같은 요청을 그대로</em> 재시도하면 되는 오류와 그 대기 시간(초).
     *
     * <p>멱등 키 잠금은 30초 뒤 스스로 풀리지만(ADR-018), 정상적인 경합은 한 요청이 커밋되는
     * 시간이면 끝난다. 1초는 그 정상 경합을 겨냥한 값이다 — 30초를 그대로 알려 주면 최악의 경우를
     * 기본값처럼 말하게 된다.
     */
    private static final Map<String, Integer> RETRY_AFTER_SECONDS =
            Map.of(OrderErrorCode.IDEMPOTENT_REQUEST_IN_FLIGHT.code(), 1);

    /**
     * 도메인 예외. 상태·코드·상세는 예외가 들고 온다.
     *
     * @param exception 도메인 예외
     * @param request   요청 (instance 필드용)
     */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> handleDomain(DomainException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatusCode.valueOf(exception.status()), exception.getMessage());
        problem.setType(URI.create(PROBLEM_TYPE_PREFIX + exception.code()));
        problem.setTitle(exception.errorCode().title());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", exception.code());
        exception.details().forEach(problem::setProperty);

        HttpHeaders headers = new HttpHeaders();
        Integer retryAfter = RETRY_AFTER_SECONDS.get(exception.code());
        if (retryAfter != null) {
            headers.set(HttpHeaders.RETRY_AFTER, Integer.toString(retryAfter));
        }
        return new ResponseEntity<>(problem, headers, HttpStatusCode.valueOf(exception.status()));
    }

    /**
     * Bean Validation 실패. 어긋난 필드를 <strong>전부</strong> 돌려준다 — 하나씩 고치며 다시
     * 보내게 하면 왕복이 필드 수만큼 늘어난다.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problemFor(CommonErrorCode.VALIDATION_FAILED, "요청 값이 유효하지 않습니다");
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> {
                    Map<String, String> entry = new LinkedHashMap<>(2);
                    entry.put("field", error.getField());
                    entry.put("reason", error.getDefaultMessage() == null ? "유효하지 않습니다"
                            : error.getDefaultMessage());
                    return entry;
                })
                // 거부된 값은 담지 않는다. 주소·연락처가 그대로 오류 응답과 로그에 실린다 (§9.3).
                .toList();
        problem.setProperty("errors", errors);
        return new ResponseEntity<>(problem, HttpStatusCode.valueOf(CommonErrorCode.VALIDATION_FAILED.status()));
    }

    /**
     * Spring MVC 가 스스로 만드는 오류(본문 파싱 실패, 헤더 누락, 타입 불일치, 지원하지 않는 API 버전
     * 등)에도 우리 {@code type}·{@code code} 를 붙인다. 그러지 않으면 같은 API 가 두 가지 오류
     * 모양을 내보내게 된다.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && isUnclassified(problem)) {
            CommonErrorCode fallback = fallbackFor(statusCode);
            problem.setType(URI.create(PROBLEM_TYPE_PREFIX + fallback.code()));
            problem.setProperty("code", fallback.code());
            if (problem.getInstance() == null && request instanceof ServletWebRequest servlet) {
                problem.setInstance(URI.create(servlet.getRequest().getRequestURI()));
            }
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    /**
     * 프레임워크가 만든 {@link ProblemDetail} 은 {@code type} 이 <strong>{@code null}</strong> 이다
     * ({@code about:blank} 가 아니다 — Spring 7 에서 실제로 확인했다). 널 비교를 빠뜨리면 여기서
     * {@code NullPointerException} 이 나고, 예외 처리기 안에서 난 예외라 응답이 <em>본문 없는 400</em>
     * 으로 조용히 나간다. 그 증상만으로는 원인을 짐작하기 어렵다.
     */
    private static boolean isUnclassified(ProblemDetail problem) {
        URI type = problem.getType();
        return type == null || "about:blank".equals(type.toString());
    }

    private static CommonErrorCode fallbackFor(HttpStatusCode statusCode) {
        if (statusCode.value() == HttpStatus.NOT_FOUND.value()) {
            return CommonErrorCode.NOT_FOUND;
        }
        if (statusCode.value() == HttpStatus.CONFLICT.value()) {
            return CommonErrorCode.CONFLICT;
        }
        return statusCode.is4xxClientError() ? CommonErrorCode.VALIDATION_FAILED : CommonErrorCode.UNAVAILABLE;
    }

    /**
     * 우리가 예상하지 못한 예외. 내부 정보를 응답에 담지 않는다 — 스택 트레이스·SQL·클래스 이름은
     * 공격자에게는 지도이고 사용자에게는 아무 의미가 없다. 원인은 로그에만 남긴다.
     *
     * @param exception 예상하지 못한 예외
     * @param request   요청
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponseException known) {
            throw known;
        }
        log.error("처리하지 못한 예외. uri={}", request.getRequestURI(), exception);
        ProblemDetail problem = problemFor(CommonErrorCode.UNAVAILABLE, "요청을 처리하지 못했습니다");
        problem.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.internalServerError().body(problem);
    }

    private static ProblemDetail problemFor(CommonErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.status()), detail);
        problem.setType(URI.create(PROBLEM_TYPE_PREFIX + code.code()));
        problem.setTitle(code.title());
        problem.setProperty("code", code.code());
        return problem;
    }
}
