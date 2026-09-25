package com.dawnline.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dawnline.messaging.config.DawnlineMessagingProperties;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ExceptionClassifier;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOffExecution;
import tools.jackson.core.JacksonException;

/**
 * §4.6 재시도/DLQ 표가 실제 설정으로 옮겨졌는지 확인한다 — 경계는 소비 측 경계표다(ADR-015 후속 정정).
 *
 * <p>일시적 · 결정적의 차이는 설정값이 아니라 <strong>배달을 거듭했을 때 DLQ 로 가는가</strong>로 본다. 컨테이너가 하듯이 실패마다
 * {@code handleRemaining} 을 부른다 — 백오프 함수가 어느 백오프를 골랐는지를 핸들러 밖에서 읽을 방법이 없고, 읽을 수 있더라도
 * 봐야 할 것은 결과다.
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
    void retryThenDlq_즉시_DLQ_의_타입은_전부_재시도하지_않는다() {
        // 경계표의 첫 행(deserialization)과 핸들러의 재시도 제외 목록은 같은 목록이다 — ConsumeFailure.immediateDlqTypes().
        for (Class<? extends Exception> type : ConsumeFailure.immediateDlqTypes()) {
            DefaultErrorHandler handler = DawnlineErrorHandlers.retryThenDlq(recorder(), retry);
            assertThat(handler.removeClassification(type)).as(type.getName()).isFalse();
        }
    }

    @Test
    void transientBackOff_는_같은_수열이_상한에서_멈춘_채_끝나지_않는다() {
        BackOffExecution execution = DawnlineErrorHandlers.transientBackOff(retry).start();

        List<Long> delays = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            delays.add(execution.nextBackOff());
        }

        assertThat(delays.subList(0, 5)).containsExactly(200L, 1_000L, 5_000L, 5_000L, 5_000L);
        assertThat(delays).doesNotContain(BackOffExecution.STOP);
    }

    @Test
    void 일시적_실패는_몇_번을_배달해도_DLQ_로_가지_않고_매번_관찰자가_듣는다() {
        // 컨테이너가 하는 대로 — 실패할 때마다 handleRemaining 이 불리고, 핸들러는 되감거나 복구기를 부른다.
        Deliveries deliveries = deliver(new CannotCreateTransactionException("x",
                new SQLTransientConnectionException("Connection is not available")), 20);

        assertThat(deliveries.recovered).as("DLQ 로 간 레코드").isEmpty();
        assertThat(deliveries.failedDeliveries).as("관찰자가 들은 실패").isEqualTo(20);
    }

    @Test
    void 결정적_실패는_첫_배달과_재시도_셋_뒤에_DLQ_로_간다() {
        Deliveries deliveries = deliver(new IllegalArgumentException("알 수 없는 티어"), 20);

        assertThat(deliveries.recovered).as("DLQ 로 간 레코드").hasSize(1);
        assertThat(deliveries.attemptsUntilRecovered).as("복구기가 불린 배달").isEqualTo(4);
    }

    @Test
    void 결정적으로_세던_레코드가_일시적_실패를_만나면_끝없는_재시도로_옮겨_간다() {
        // resetStateOnExceptionChange — 판정은 레코드의 첫 실패에서 한 번이 아니라 예외가 바뀔 때마다 다시 한다.
        List<Exception> failures = new ArrayList<>();
        failures.add(new IllegalArgumentException("첫 실패는 결정적"));
        failures.add(new IllegalArgumentException("둘째도"));
        for (int i = 0; i < 18; i++) {
            failures.add(new CannotCreateTransactionException("그다음 DB 가 내려갔다"));
        }

        Deliveries deliveries = deliver(failures);

        assertThat(deliveries.recovered).isEmpty();
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

    /** 배달 결과 — 복구기(DLQ)가 받은 레코드와 관찰자가 들은 실패 수. */
    private static final class Deliveries {
        private final List<ConsumerRecord<?, ?>> recovered = new ArrayList<>();
        private int failedDeliveries;
        private int attemptsUntilRecovered;
    }

    private Deliveries deliver(Exception failure, int times) {
        List<Exception> failures = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            failures.add(failure);
        }
        return deliver(failures);
    }

    /**
     * 같은 레코드를 실패마다 핸들러에 준다 — 대기는 1ms 로 줄인 같은 모양의 설정이다. 복구기가 불리면 멈춘다(그 레코드는 끝났다).
     */
    @SuppressWarnings("unchecked")
    private Deliveries deliver(List<Exception> failures) {
        Deliveries deliveries = new Deliveries();
        DawnlineMessagingProperties.Retry fast =
                new DawnlineMessagingProperties.Retry(true, Duration.ofMillis(1), 1.0, Duration.ofMillis(1), 3, ".dlq");
        RetryListener listener = (record, exception, attempt) -> deliveries.failedDeliveries++;
        DefaultErrorHandler handler = DawnlineErrorHandlers.retryThenDlq(
                (record, exception) -> deliveries.recovered.add(record), fast, listener);
        Consumer<Object, Object> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("dawnline.order.placed.v1", 3, 41L, "k", "v");

        int attempt = 0;
        for (Exception failure : failures) {
            attempt++;
            try {
                handler.handleRemaining(new ListenerExecutionFailedException("리스너 실패", failure),
                        List.of(record), consumer, container);
            } catch (RuntimeException rewound) {
                // 되감았다 — 컨테이너는 이것(패키지 전용 RecordInRetryException)을 삼키고 같은 오프셋부터 다시 폴한다.
                if (!rewound.getClass().getSimpleName().equals("RecordInRetryException")) {
                    throw rewound;
                }
            }
            if (!deliveries.recovered.isEmpty()) {
                deliveries.attemptsUntilRecovered = attempt;
                break;
            }
        }
        return deliveries;
    }
}
