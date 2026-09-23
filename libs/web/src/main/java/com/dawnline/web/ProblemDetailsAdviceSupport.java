package com.dawnline.web;

import com.dawnline.common.error.CommonErrorCode;
import com.dawnline.common.error.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 도메인 예외 → RFC 9457 Problem Details. 서비스들이 공유하는 오류 응답의 모양
 * (CLAUDE.md 「코딩 컨벤션」, [ADR-049]).
 *
 * <h2>왜 여기에 있는가</h2>
 * 이 클래스는 order · tracking · dispatch 세 서비스에 <strong>거의 글자 그대로</strong> 있던
 * 것이다. 정규화해 diff 를 뜨면 갈라지는 칸은 {@link #retryAfterSeconds()} <strong>하나</strong>
 * 였다. 넷째(ops-api)가 오기 전에 뽑았고, 자리가 {@code libs/common} 이 아니라
 * {@code libs/web} 인 이유는 이 코드가 Spring 을 알기 때문이다 — {@code libs/common} 의
 * {@code main} 은 프레임워크 비의존으로 남는다(ArchUnit 규칙 10).
 *
 * <p>하위 클래스는 {@code @RestControllerAdvice} 를 붙여 서비스의 <strong>유일한</strong>
 * 어드바이스가 된다. 「가진 서비스는 전부 이 기반을 쓴다」는 ArchUnit 규칙 9 가 <em>열거가
 * 아니라 조건</em>으로 본다.
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
 * 그 목록을 서비스가 {@link #retryAfterSeconds()} 로 말한다.
 */
public abstract class ProblemDetailsAdviceSupport extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdviceSupport.class);

    /** {@code type} URI 의 접두어. 계약 파일의 {@code $id} 와 같은 호스트를 쓴다. */
    public static final String PROBLEM_TYPE_PREFIX = "https://dawnline.internal/problems/";

    /**
     * 예외가 직접 실어 보내는 대기 시간. 레이트 리밋처럼 <em>얼마나</em> 기다려야 하는지가
     * 상황마다 다른 경우에 쓴다 — {@link #retryAfterSeconds()} 의 고정값보다 우선한다.
     */
    public static final String RETRY_AFTER_DETAIL = "retryAfterSeconds";

    /**
     * 잠시 후 <em>같은 요청을 그대로</em> 재시도하면 되는 오류와 그 대기 시간(초).
     *
     * <p><strong>기본값을 주지 않는 것이 이 훅의 요점이다</strong> ([ADR-049] 결정 3).
     * {@code Map.of()} 를 기본으로 두면 해당 오류가 없는 서비스는 아무것도 쓰지 않게 되고,
     * 그러면 <em>왜</em> 비었는지가 코드에서 사라진다 — 지금 그 이유는 서비스마다 다르다.
     * 「넣지 않기로 한 판단도 기록한다」(CLAUDE.md 불변규칙 11)와 같은 축이고, 추상 메서드는
     * 그 기록을 강제한다.
     *
     * @return 오류 코드({@code ErrorCode.code()}) → 대기 시간(초). 없으면 빈 맵
     */
    protected abstract Map<String, Integer> retryAfterSeconds();

    /**
     * 도메인 예외. 상태·코드·상세는 예외가 들고 온다.
     *
     * @param exception 도메인 예외
     * @param request   요청 (instance 필드용)
     * @return RFC 9457 응답
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
        retryAfterFor(exception).ifPresent(seconds ->
                headers.set(HttpHeaders.RETRY_AFTER, Integer.toString(seconds)));
        return new ResponseEntity<>(problem, headers, HttpStatusCode.valueOf(exception.status()));
    }

    /** 예외가 실어 온 값이 먼저이고, 없으면 코드별 고정값을 쓴다. */
    private Optional<Integer> retryAfterFor(DomainException exception) {
        Object fromDetails = exception.details().get(RETRY_AFTER_DETAIL);
        if (fromDetails instanceof Number seconds) {
            return Optional.of(Math.max(1, seconds.intValue()));
        }
        return Optional.ofNullable(retryAfterSeconds().get(exception.code()));
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
     * @return 내부 정보를 뺀 500 응답
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
