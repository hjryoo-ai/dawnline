package com.dawnline.common.error;

import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 모든 도메인 예외의 상위 타입.
 *
 * <p>프레임워크에 의존하지 않는다(CLAUDE.md 불변규칙 5). HTTP 로의 변환은 각 서비스
 * {@code adapter.in.web} 의 단일 {@code @ControllerAdvice} 가 담당한다.
 *
 * <p>메시지 외에 <strong>기계가 읽을 수 있는</strong> 정보를 함께 담는다.
 * <ul>
 *   <li>{@link #errorCode()} — 안정적인 코드 + HTTP 상태 + 기본 title</li>
 *   <li>{@link #details()} — 문제 상세(필드명, 현재 상태, 충돌 대상 등). RFC 9457 확장 멤버로 그대로 내보낼 수 있다.</li>
 * </ul>
 *
 * <p>{@code details} 의 값은 JSON 으로 직렬화 가능한 단순 값(문자열·숫자·불리언)만 넣는다.
 * 전체 주소·고객 식별 정보 같은 개인정보는 넣지 않는다(CLAUDE.md 로그 규칙).
 */
public class DomainException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public DomainException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of(), null);
    }

    public DomainException(ErrorCode errorCode, String message, Map<String, Object> details) {
        this(errorCode, message, details, null);
    }

    public DomainException(ErrorCode errorCode, String message, Map<String, Object> details, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        // 삽입 순서를 유지해 오류 응답이 결정론적으로 나오게 한다(Map.copyOf 는 순서를 보장하지 않는다).
        this.details = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(details, "details")));
    }

    /** 안정적인 오류 코드. */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** {@code errorCode().code()} 단축. */
    public String code() {
        return errorCode.code();
    }

    /** {@code errorCode().status()} 단축. */
    public int status() {
        return errorCode.status();
    }

    /** 변경 불가능한 상세 맵. 비어 있을 수 있다. */
    public Map<String, Object> details() {
        return details;
    }
}
