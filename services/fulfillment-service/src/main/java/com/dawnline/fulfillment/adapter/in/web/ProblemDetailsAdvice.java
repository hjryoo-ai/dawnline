package com.dawnline.fulfillment.adapter.in.web;

import com.dawnline.web.ProblemDetailsAdviceSupport;
import java.util.Map;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 이 서비스의 <strong>유일한</strong> {@code @ControllerAdvice} 다. 오류 응답의 모양이 한 곳에서만
 * 정해져야 운영 도구가 그것을 계약으로 삼을 수 있다.
 *
 * <p>모양 자체는 {@link ProblemDetailsAdviceSupport} 에 있다 (ADR-049). 여기 남는 것은 이
 * 서비스만의 답 하나 — {@link #retryAfterSeconds()} 다.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ProblemDetailsAdviceSupport {

    /**
     * 잠시 후 <em>같은 요청을 그대로</em> 재시도하면 되는 오류와 그 대기 시간(초).
     *
     * <p>이 서비스에는 그런 오류가 없다 — 조기 마감의 409 {@code wave-not-open} 은 재시도해도 결과가 같고,
     * 그 409 자체가 앞의 요청이 적용됐는지를 말한다(ADR-054 결정 6). <strong>비었다는 것을 빈칸이 아니라
     * 이 메서드로 말하는 이유</strong>는 ADR-049 결정 3 이다 — 기본값이 있으면 이 판단이 코드에서 사라진다.
     */
    @Override
    protected Map<String, Integer> retryAfterSeconds() {
        return Map.of();
    }
}
