package com.dawnline.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.dawnline.messaging.outbox.PublishFailureClassifier.Kind;
import com.dawnline.messaging.outbox.PublishFailureClassifier.Phase;
import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 발행 실패 판정 (DESIGN.md §4.6, ADR-015).
 *
 * <p>이 판정이 틀리면 결과가 갈린다 — 결정적인데 일시적으로 보면 예전의 무한 재시도(head-of-line
 * blocking)로 돌아가고, 반대면 회복 가능한 행이 사람 손을 기다린다.
 */
class PublishFailureClassifierTest {

    private final PublishFailureClassifier classifier = new PublishFailureClassifier();

    static Object[][] 조립_단계_예외() {
        return new Object[][] {
            {new IllegalArgumentException("eventType 형식 위반")},
            {new IllegalStateException("schemaVersion 헤더 없음")},
            {new NullPointerException("partitionKey")},
            {new RuntimeException("알 수 없는 조립 실패")},
        };
    }

    @ParameterizedTest
    @MethodSource("조립_단계_예외")
    void 조립_단계는_무조건_결정적이다(RuntimeException failure) {
        // 이 단계는 네트워크도 브로커도 건드리지 않고 저장된 바이트만 읽는다.
        // 같은 행을 다시 읽으면 같은 예외가 나므로 재시도가 순수한 낭비다.
        assertThat(classifier.classify(Phase.ASSEMBLY, failure)).isEqualTo(Kind.DETERMINISTIC);
    }

    static Object[][] 일시적_전송_예외() {
        return new Object[][] {
            {new TimeoutException("브로커 응답 없음")},
            {new NetworkException("연결 끊김")},
            {new KafkaException("브로커 오류")},
            {new UnknownTopicOrPartitionException("토픽 없음")},
            {new IOException("소켓 오류")},
            {new IllegalStateException("프로듀서가 닫힘")},
        };
    }

    @ParameterizedTest
    @MethodSource("일시적_전송_예외")
    void 전송_단계의_기본값은_일시적이다(Throwable failure) {
        assertThat(classifier.classify(Phase.DELIVERY, failure)).isEqualTo(Kind.TRANSIENT);
    }

    @Test
    void 전송_단계에서_토픽_부재는_일시적이다() {
        // 기동 직후 토픽이 아직 안 만들어진 상황은 기다리면 풀린다. 격리하면 안 된다.
        assertThat(classifier.classify(Phase.DELIVERY, new UnknownTopicOrPartitionException("아직 없음")))
                .isEqualTo(Kind.TRANSIENT);
    }

    @Test
    void 전송_단계에서_직렬화_실패는_결정적이다() {
        // §4.6 이 명시적으로 결정적이라고 정한 경우. 같은 값을 다시 직렬화해도 같다.
        assertThat(classifier.classify(Phase.DELIVERY, new SerializationException("직렬화 실패")))
                .isEqualTo(Kind.DETERMINISTIC);
    }

    @Test
    void 전송_단계에서_레코드_크기_초과는_결정적이다() {
        // 행이 작아지지 않으므로 재시도가 무의미하다.
        assertThat(classifier.classify(Phase.DELIVERY, new RecordTooLargeException("너무 큼")))
                .isEqualTo(Kind.DETERMINISTIC);
    }

    @Test
    void ExecutionException_래퍼를_벗기고_원인으로_판정한다() {
        // future.get() 은 원인을 ExecutionException 에 싼다. 벗기지 않으면 모든 전송 실패가
        // 같은 타입으로 보여 판정이 무의미해진다.
        assertThat(classifier.classify(Phase.DELIVERY,
                new ExecutionException(new SerializationException("직렬화 실패"))))
                .isEqualTo(Kind.DETERMINISTIC);
        assertThat(classifier.classify(Phase.DELIVERY, new ExecutionException(new NetworkException("끊김"))))
                .isEqualTo(Kind.TRANSIENT);
    }

    @Test
    void CompletionException_래퍼도_벗긴다() {
        assertThat(classifier.classify(Phase.DELIVERY,
                new CompletionException(new RecordTooLargeException("너무 큼"))))
                .isEqualTo(Kind.DETERMINISTIC);
    }

    @Test
    void 중첩된_래퍼도_끝까지_벗긴다() {
        assertThat(classifier.classify(Phase.DELIVERY,
                new ExecutionException(new CompletionException(new SerializationException("직렬화 실패")))))
                .isEqualTo(Kind.DETERMINISTIC);
    }

    @Test
    void 원인이_없는_래퍼는_일시적이다() {
        // 벗길 것이 없으면 판단 근거도 없다 → 보수적으로 일시적.
        assertThat(classifier.classify(Phase.DELIVERY, new ExecutionException("원인 없음", null)))
                .isEqualTo(Kind.TRANSIENT);
    }

    @Test
    void null_인자는_거부한다() {
        assertThatNullPointerException()
                .isThrownBy(() -> classifier.classify(null, new RuntimeException()));
        assertThatNullPointerException()
                .isThrownBy(() -> classifier.classify(Phase.DELIVERY, null));
    }
}
