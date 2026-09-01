package com.dawnline.common.error;

/**
 * 도메인 오류의 <strong>안정적인</strong> 식별자.
 *
 * <p>여기서 정의하는 것은 세 가지뿐이다.
 * <ul>
 *   <li>{@link #code()} — 기계가 읽는 안정적인 문자열 코드(kebab-case). 클라이언트 분기 기준이며 절대 바뀌지 않는다.</li>
 *   <li>{@link #status()} — 이 오류에 대응하는 HTTP 상태 코드.</li>
 *   <li>{@link #title()} — RFC 9457 {@code title} 의 기본값(사람이 읽는 한 줄 요약).</li>
 * </ul>
 *
 * <p>libs/common 은 프레임워크 비의존이므로(CLAUDE.md 불변규칙 5) Spring 의
 * {@code ProblemDetail} 을 직접 쓰지 않는다. 각 서비스의 {@code adapter.in.web} 에 있는
 * 단일 {@code @ControllerAdvice} 가 이 값들로 RFC 9457 Problem Details 응답을 만든다.
 * {@code type} URI 는 서비스별 문서 URL 규칙에 따라 웹 어댑터에서 조립한다.
 *
 * <p>인터페이스로 둔 이유: 공통 코드는 {@link CommonErrorCode} 로 제공하되,
 * 각 서비스가 자기 도메인 전용 코드(enum)를 추가로 정의할 수 있어야 하기 때문이다.
 */
public interface ErrorCode {

    /** 기계가 읽는 안정적인 오류 코드. 예: {@code "validation-failed"}. */
    String code();

    /** 이 오류에 대응하는 HTTP 상태 코드. */
    int status();

    /** RFC 9457 {@code title} 기본값. */
    String title();
}
