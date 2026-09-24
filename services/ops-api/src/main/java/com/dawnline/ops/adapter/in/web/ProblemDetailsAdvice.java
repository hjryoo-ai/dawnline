package com.dawnline.ops.adapter.in.web;

import com.dawnline.common.error.CommonErrorCode;
import com.dawnline.web.ProblemDetailsAdviceSupport;
import java.util.Map;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 이 서비스의 <strong>유일한</strong> {@code @ControllerAdvice} 다. 모양은 {@link ProblemDetailsAdviceSupport}
 * 에 있다(ADR-049). 여기 남는 것은 이 서비스만의 답 하나 — {@link #retryAfterSeconds()} 다.
 *
 * <p>코어의 거절·무응답은 예외가 아니라 {@link CommandResponses} 가 만드는 응답이다 — 그 응답은 감사 id
 * 헤더를 싣고, 거절은 코어의 본문을 그대로 전달해야 해서 예외 매핑의 모양에 맞지 않는다.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ProblemDetailsAdviceSupport {

    /**
     * 감사 기록을 쓰지 못해 위임하지 않은 경우({@code unavailable}) 잠시 뒤 같은 요청을 다시 보내면 된다 —
     * 아무것도 적용되지 않았다. 5초는 DB 커넥션 획득 타임아웃과 같은 자릿수다.
     */
    @Override
    protected Map<String, Integer> retryAfterSeconds() {
        return Map.of(CommonErrorCode.UNAVAILABLE.code(), 5);
    }
}
