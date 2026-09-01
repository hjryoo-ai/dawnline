package com.dawnline.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.config.DawnlineMessagingProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ExceptionClassifier;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.BackOffExecution;
import tools.jackson.core.JacksonException;

/**
 * §4.6 재시도/DLQ 표가 실제 설정으로 옮겨졌는지 확인한다.
 */
class DawnlineErrorHandlersTest {

    private final DawnlineMessagingProperties.Retry retry =
            new DawnlineMessagingProperties.Retry(true, Duration.ofMillis(200), 5.0, Duration.ofSeconds(5), 3, ".dlq");

    @Test
    void backOff_설계서의_200ms_1s_5s_수열을_만든다() {
        BackOffExecution execution = DawnlineErrorHandlers.backOff(retry).start();

        List<Long> delays = new ArrayList<>();
        for (long next = execution.nextBackOff(); next != BackOffExecution.STOP; next = execution.nextBackOff()) {
            delays.add(next);
        }

        assertThat(delays).containsExactly(200L, 1_000L, 5_000L);
    }

    @Test
    void backOff_jitter가_0이라_결정론적이다() {
        // 지터가 켜져 있으면 같은 설정으로도 값이 매번 달라져 위 어설션이 성립하지 않는다.
        List<Long> first = drain();
        List<Long> second = drain();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void backOff_maxInterval을_넘지_않는다() {
        DawnlineMessagingProperties.Retry longRetry = new DawnlineMessagingProperties.Retry(
                true, Duration.ofMillis(200), 5.0, Duration.ofSeconds(5), 5, ".dlq");
        BackOffExecution execution = DawnlineErrorHandlers.backOff(longRetry).start();

        List<Long> delays = new ArrayList<>();
        for (long next = execution.nextBackOff(); next != BackOffExecution.STOP; next = execution.nextBackOff()) {
            delays.add(next);
        }

        assertThat(delays).containsExactly(200L, 1_000L, 5_000L, 5_000L, 5_000L);
    }

    @Test
    void retryThenDlq_봉투_파싱_실패는_재시도하지_않는다() {
        // §4.6 두 번째 줄: 역직렬화 실패/스키마 불일치는 즉시 DLQ.
        DefaultErrorHandler handler = DawnlineErrorHandlers.retryThenDlq(recorder(), retry);

        // removeClassification 은 현재 분류값을 돌려준다. false = 재시도 대상 아님.
        assertThat(handler.removeClassification(NonRetryableEventException.class)).isFalse();
        assertThat(handler.removeClassification(JacksonException.class)).isFalse();
    }

    @Test
    void retryThenDlq_일반_예외는_분류를_두지_않아_기본값인_재시도_대상이다() {
        DefaultErrorHandler handler = DawnlineErrorHandlers.retryThenDlq(recorder(), retry);

        // 분류가 없으면 기본값(재시도)이 적용된다. §4.6 첫 줄의 "일시적 오류" 가 여기에 해당한다.
        assertThat(handler.removeClassification(IllegalStateException.class)).isNull();
    }

    @Test
    void 역직렬화_예외는_스프링_기본_fatal_목록에_이미_있다() {
        // 우리가 따로 추가하지 않는 이유를 코드로 남긴다. 프레임워크 기본값이 바뀌면 이 테스트가 알려 준다.
        assertThat(ExceptionClassifier.defaultFatalExceptionsList()).contains(DeserializationException.class);
    }

    private List<Long> drain() {
        BackOffExecution execution = DawnlineErrorHandlers.backOff(retry).start();
        List<Long> delays = new ArrayList<>();
        for (long next = execution.nextBackOff(); next != BackOffExecution.STOP; next = execution.nextBackOff()) {
            delays.add(next);
        }
        return delays;
    }

    private static ConsumerRecordRecoverer recorder() {
        return (ConsumerRecord<?, ?> record, Exception exception) -> { };
    }
}
