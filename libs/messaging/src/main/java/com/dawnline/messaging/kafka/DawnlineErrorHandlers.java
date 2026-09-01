package com.dawnline.messaging.kafka;

import com.dawnline.messaging.config.DawnlineMessagingProperties;
import java.util.Objects;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * §4.6 의 재시도/DLQ 표를 그대로 옮긴 에러 핸들러 팩토리.
 *
 * <table>
 *   <caption>DESIGN.md §4.6</caption>
 *   <tr><th>상황</th><th>처리</th><th>여기서의 구현</th></tr>
 *   <tr><td>일시적 오류 (DB 타임아웃, Redis 연결)</td><td>지수 백오프 3회 (200ms·1s·5s)</td>
 *       <td>{@link ExponentialBackOff}(200ms, ×5, 최대 3회)</td></tr>
 *   <tr><td>역직렬화 실패/스키마 불일치</td><td>즉시 DLQ</td>
 *       <td>{@link JacksonException}·{@link NonRetryableEventException} 을 재시도 대상에서 제외.
 *           Spring Kafka 기본 fatal 목록에 이미 있는 {@code DeserializationException}·
 *           {@code MessageConversionException}·{@code ClassCastException} 도 그대로 즉시 DLQ 다.</td></tr>
 *   <tr><td>비즈니스 규칙 위반</td><td>DLQ 아님. warn + 메트릭</td>
 *       <td>여기까지 오지 않는다.
 *           {@code IdempotentConsumer} 가 {@code EventRejectedException} 을 흡수하고 커밋한다.</td></tr>
 * </table>
 *
 * <p>백오프 값을 하드코딩하지 않고 {@code multiplier} 로 표현한 이유는, 200ms·1s·5s 가 정확히
 * 5배씩 커지는 수열이라 곱셈 하나로 설계서의 표를 재현할 수 있기 때문이다.
 * {@code ExponentialBackOff} 의 기본 jitter 는 0이므로 대기 시간이 결정론적이다(테스트에서 확인).
 */
public final class DawnlineErrorHandlers {

    private DawnlineErrorHandlers() {
    }

    /**
     * §4.6 의 재시도 → DLQ 핸들러.
     *
     * @param recoverer 재시도 소진 또는 즉시 실패 시 호출될 복구기 (보통 {@link DlqRecordRecoverer})
     * @param retry     재시도 설정
     */
    public static DefaultErrorHandler retryThenDlq(ConsumerRecordRecoverer recoverer,
            DawnlineMessagingProperties.Retry retry) {
        Objects.requireNonNull(recoverer, "recoverer");
        Objects.requireNonNull(retry, "retry");

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff(retry));
        // 재시도해도 결과가 같은 실패는 첫 배달에서 바로 복구기로 보낸다 (§4.6 두 번째 줄).
        handler.addNotRetryableExceptions(NonRetryableEventException.class, JacksonException.class);
        return handler;
    }

    /**
     * §4.6 의 백오프 수열을 만든다. {@code maxAttempts} 를 소진하면 {@code BackOffExecution.STOP} 을
     * 돌려주고, 그 시점에 {@link DefaultErrorHandler} 가 복구기(DLQ)를 부른다.
     *
     * @param retry 재시도 설정
     */
    public static BackOff backOff(DawnlineMessagingProperties.Retry retry) {
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        backOff.setMaxAttempts(retry.maxAttempts());
        backOff.setJitter(0);
        return backOff;
    }
}
