package com.dawnline.messaging.kafka;

import com.dawnline.messaging.FailureKind;
import com.dawnline.messaging.config.DawnlineMessagingProperties;
import java.time.Duration;
import java.util.Objects;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * §4.6 의 재시도/DLQ 표를 그대로 옮긴 에러 핸들러 팩토리 — 경계는 소비 측 경계표다(ADR-015 후속 정정 2026-09-25).
 *
 * <table>
 *   <caption>DESIGN.md §4.6</caption>
 *   <tr><th>상황</th><th>처리</th><th>여기서의 구현</th></tr>
 *   <tr><td>일시적 오류 (DB 연결 · 타임아웃, Redis, 분류되지 않은 것)</td><td>백오프로 끝없이 재시도 — 파티션이 멈춘다</td>
 *       <td>{@link #transientBackOff}(200ms, ×5, 5초에서 멈춘 채 되풀이, 횟수 상한 없음)</td></tr>
 *   <tr><td>결정적 오류 ({@code IllegalArgumentException}, 도메인 예외)</td><td>3회 재시도 뒤 DLQ</td>
 *       <td>{@link #backOff}(200ms, ×5, 최대 3회)</td></tr>
 *   <tr><td>역직렬화 실패/스키마 불일치</td><td>즉시 DLQ</td>
 *       <td>{@link ConsumeFailure#immediateDlqTypes()} 를 재시도 대상에서 제외한다. Spring Kafka 의 기본 치명 목록과 이 저장소의
 *           둘({@code JacksonException} · {@link NonRetryableEventException})이다.</td></tr>
 *   <tr><td>비즈니스 규칙 위반</td><td>DLQ 아님. warn + 메트릭</td>
 *       <td>여기까지 오지 않는다.
 *           {@code IdempotentConsumer} 가 {@code EventRejectedException} 을 흡수하고 커밋한다.</td></tr>
 * </table>
 *
 * <p>일시적과 결정적을 가르는 것은 {@link DefaultErrorHandler#setBackOffFunction} 이다 — 레코드의 첫 실패에서
 * {@link ConsumeFailure#of} 로 행을 판정하고 그 행의 백오프를 고른다. 같은 레코드가 다른 예외로 실패하면 판정을 다시 한다
 * ({@code resetStateOnExceptionChange}) — 결정적으로 3회를 세던 레코드가 DB 장애를 만나면 끝없는 재시도로 옮겨 간다.
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
     * @param listeners 재시도 관찰자 — 보통 {@link ConsumerRetryObserver}(카운터 · 나이 게이지)
     */
    public static DefaultErrorHandler retryThenDlq(ConsumerRecordRecoverer recoverer,
            DawnlineMessagingProperties.Retry retry, RetryListener... listeners) {
        Objects.requireNonNull(recoverer, "recoverer");
        Objects.requireNonNull(retry, "retry");

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff(retry));
        // 재시도해도 결과가 같은 실패는 첫 배달에서 바로 복구기로 보낸다 (§4.6 역직렬화 행 — 경계표의 첫 행).
        ConsumeFailure.immediateDlqTypes().forEach(handler::addNotRetryableExceptions);
        // 나머지는 경계표가 가른다 — 일시적이면 끝없이, 결정적이면 3회.
        handler.setBackOffFunction((record, failure) ->
                ConsumeFailure.of(failure).kind() == FailureKind.TRANSIENT ? transientBackOff(retry) : backOff(retry));
        handler.setResetStateOnExceptionChange(true);
        handler.setRetryListeners(listeners);
        return handler;
    }

    /**
     * §4.6 의 백오프 수열(결정적 실패). {@code maxAttempts} 를 소진하면 {@code BackOffExecution.STOP} 을
     * 돌려주고, 그 시점에 {@link DefaultErrorHandler} 가 복구기(DLQ)를 부른다.
     *
     * @param retry 재시도 설정
     */
    public static BackOff backOff(DawnlineMessagingProperties.Retry retry) {
        ExponentialBackOff backOff = exponential(retry);
        backOff.setMaxAttempts(retry.maxAttempts());
        return backOff;
    }

    /**
     * 일시적 실패의 백오프 — 같은 수열이 {@code maxInterval} 에서 멈춘 채 <strong>끝나지 않는다.</strong> {@code STOP} 을 돌려주지
     * 않으므로 복구기(DLQ)가 불리지 않고, 그 파티션은 풀릴 때까지 멈춘다(ADR-015 후속 정정 결정 2).
     *
     * <p>한 바퀴(폴 → 리스너가 실패할 때까지 → 백오프)가 {@code max.poll.interval.ms} 안에 드는 한 컨슈머는 그룹에 남는다 —
     * 백오프의 상한이 {@code maxInterval}(5초)인 이유 중 하나다({@code ConsumerRetryIT} 가 본다).
     *
     * @param retry 재시도 설정
     */
    public static BackOff transientBackOff(DawnlineMessagingProperties.Retry retry) {
        ExponentialBackOff backOff = exponential(retry);
        backOff.setMaxAttempts(Long.MAX_VALUE);
        backOff.setMaxElapsedTime(Long.MAX_VALUE);
        return backOff;
    }

    /**
     * 백오프 상한은 폴 간격 상한보다 짧아야 한다 — 기동에서 거부한다(보존 기간의 순서 검증과 같은 형태, 예: dispatch 의 보존 설정).
     *
     * <p>끝없는 재시도의 한 바퀴는 폴 → 리스너가 실패할 때까지 → 백오프다. 백오프 하나가 {@code max.poll.interval.ms} 를 넘으면
     * 컨슈머가 그룹에서 쫓겨나고, 리밸런스가 끝나면 같은 레코드를 다시 재시도하며 또 쫓겨난다 — 끝없는 재시도가 끝없는 리밸런스가
     * 된다(근거: 관측(재현됨) — {@code ConsumerRetryIT} 의 음성 표본, 백오프 상한 5초 · 폴 간격 3초에서 멤버 id 가 바뀌었다). 운영
     * 기본값(5초 · 300초)은 이 관계를 우연히 만족하고 있었다 — 설정 하나가 바뀌는 날 조용히 깨지는 관계라 기동에서 본다.
     *
     * <p>이 검사는 필요조건이다. 한 바퀴에는 리스너의 실패 시간(커넥션 대기 — Hikari 30초)도 든다. 그 시간은 이 설정이 모른다.
     *
     * @param retry           재시도 설정
     * @param maxPollInterval 컨슈머 팩토리의 {@code max.poll.interval.ms}
     * @throws IllegalArgumentException 백오프 상한이 폴 간격 상한보다 짧지 않으면
     */
    public static void requireBackOffWithinPollInterval(DawnlineMessagingProperties.Retry retry, Duration maxPollInterval) {
        Objects.requireNonNull(retry, "retry");
        Objects.requireNonNull(maxPollInterval, "maxPollInterval");
        if (retry.maxInterval().compareTo(maxPollInterval) >= 0) {
            throw new IllegalArgumentException(("dawnline.messaging.retry.max-interval(%s)이 컨슈머의 max.poll.interval.ms(%s)보다 짧아야 합니다 — "
                    + "일시적 실패는 끝없이 재시도하므로, 백오프 하나가 폴 간격을 넘으면 컨슈머가 그룹에서 쫓겨나 리밸런스를 되풀이합니다")
                    .formatted(retry.maxInterval(), maxPollInterval));
        }
    }

    private static ExponentialBackOff exponential(DawnlineMessagingProperties.Retry retry) {
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        backOff.setJitter(0);
        return backOff;
    }
}
