package com.dawnline.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.dawnline.messaging.outbox.PublishFailureClassifier.Kind;
import com.dawnline.messaging.outbox.PublishFailureClassifier.Phase;
import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.errors.UnsupportedVersionException;
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

    static Object[][] 재시도하면_풀리는_전송_예외() {
        return new Object[][] {
            {new TimeoutException("브로커 응답 없음")},
            {new NetworkException("연결 끊김")},
            {new NotEnoughReplicasException("ISR 부족")},
            // 기동 직후 토픽이 아직 안 만들어진 상황. 기다리면 풀리므로 격리하면 안 된다.
            {new UnknownTopicOrPartitionException("토픽 없음")},
        };
    }

    @ParameterizedTest
    @MethodSource("재시도하면_풀리는_전송_예외")
    void Kafka가_재시도_가능이라고_한_예외는_일시적이다(Throwable failure) {
        assertThat(classifier.classify(Phase.DELIVERY, failure)).isEqualTo(Kind.TRANSIENT);
    }

    static Object[][] 재시도해도_같은_전송_예외() {
        return new Object[][] {
            // 같은 값을 다시 직렬화해도 같다 (§4.6 이 명시적으로 결정적이라고 정한 경우).
            {new SerializationException("직렬화 실패")},
            // 행이 작아지지 않는다.
            {new RecordTooLargeException("너무 큼")},
            // 토픽 이름이 규칙 위반이다. 브로커가 절대 받아 주지 않는다.
            {new InvalidTopicException("토픽 이름 위반")},
            // ACL 이 없다. 사람이 권한을 주기 전까지 재시도는 무의미하다.
            {new TopicAuthorizationException("권한 없음")},
            {new ClusterAuthorizationException("권한 없음")},
            // 브로커가 이 API 버전을 모른다.
            {new UnsupportedVersionException("버전 불일치")},
        };
    }

    @ParameterizedTest
    @MethodSource("재시도해도_같은_전송_예외")
    void Kafka가_비재시도로_분류한_예외는_결정적이다(Throwable failure) {
        // 이 목록을 손으로 유지하지 않는 것이 요점이다 — 하나라도 빠뜨리면 그 예외를 낸 행이
        // 일시적으로 분류돼 뒤의 모든 이벤트를 영구히 막는다(ADR-015 가 없애려던 상태).
        assertThat(classifier.classify(Phase.DELIVERY, failure)).isEqualTo(Kind.DETERMINISTIC);
    }

    static Object[][] Kafka가_분류하지_않은_예외() {
        return new Object[][] {
            {new IOException("소켓 오류")},
            {new IllegalStateException("프로듀서가 닫힘")},
            {new RuntimeException("알 수 없음")},
        };
    }

    @ParameterizedTest
    @MethodSource("Kafka가_분류하지_않은_예외")
    void 판단_근거가_없으면_일시적이다(Throwable failure) {
        // ADR-015: 격리는 사람의 개입을 요구하므로 보수적인 쪽이 기본값이다.
        assertThat(classifier.classify(Phase.DELIVERY, failure)).isEqualTo(Kind.TRANSIENT);
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
