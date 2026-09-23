package com.dawnline.web.samples.bad;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 규칙 9 의 <strong>위반</strong> 표본 — 기반 없이 자기 모양을 만드는 어드바이스다.
 *
 * <p>컴파일되는 표본이어야 음성 검증이 성립한다(DESIGN.md §13). 이 클래스가 실제로 하는 일은
 * 규칙이 잡아야 하는 바로 그것이다: 같은 API 가 두 가지 오류 모양을 내보내게 만든다.
 */
@RestControllerAdvice
public class OwnShapeAdvice {

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handle(IllegalStateException exception) {
        return ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatusCode.valueOf(500), exception.getMessage());
    }
}
