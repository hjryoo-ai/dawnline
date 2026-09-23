package com.dawnline.order.adapter.in.web;

import com.dawnline.order.domain.OrderErrorCode;
import com.dawnline.web.ProblemDetailsAdviceSupport;
import java.util.Map;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 이 서비스의 <strong>유일한</strong> {@code @ControllerAdvice} 다. 오류 응답의 모양이 한 곳에서만
 * 정해져야 클라이언트가 그것을 계약으로 삼을 수 있다.
 *
 * <p>모양 자체는 {@link ProblemDetailsAdviceSupport} 에 있다 (ADR-049). 여기 남는 것은 이
 * 서비스만의 답 하나 — {@link #retryAfterSeconds()} 다.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ProblemDetailsAdviceSupport {

    /**
     * 잠시 후 <em>같은 요청을 그대로</em> 재시도하면 되는 오류와 그 대기 시간(초).
     *
     * <p>멱등 키 잠금은 30초 뒤 스스로 풀리지만(ADR-018), 정상적인 경합은 한 요청이 커밋되는
     * 시간이면 끝난다. 1초는 그 정상 경합을 겨냥한 값이다 — 30초를 그대로 알려 주면 최악의 경우를
     * 기본값처럼 말하게 된다.
     */
    private static final Map<String, Integer> RETRY_AFTER_SECONDS =
            Map.of(OrderErrorCode.IDEMPOTENT_REQUEST_IN_FLIGHT.code(), 1);

    @Override
    protected Map<String, Integer> retryAfterSeconds() {
        return RETRY_AFTER_SECONDS;
    }
}
