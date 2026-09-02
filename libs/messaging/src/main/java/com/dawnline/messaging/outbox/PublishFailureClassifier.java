package com.dawnline.messaging.outbox;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.common.errors.ApiException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;

/**
 * 발행 실패가 <strong>결정적</strong>인지 <strong>일시적</strong>인지 판정한다 (DESIGN.md §4.6, ADR-015).
 *
 * <p>이 구분이 릴레이의 진행 보장을 결정한다.
 *
 * <ul>
 *   <li><strong>결정적</strong> — 몇 번을 재시도해도 같은 결과다. 재시도를 유지하면 그 행이
 *       {@code created_at} 순서상 맨 앞에 서서 뒤의 모든 이벤트를 영구히 막는다.
 *       그래서 격리({@code failed_at})하고 다음 행으로 넘어간다.</li>
 *   <li><strong>일시적</strong> — 기다리면 풀린다. 여기서 격리하면 브로커가 잠깐 흔들렸다는 이유로
 *       멀쩡한 이벤트가 사람 손을 기다리게 된다. 그래서 그대로 두고 다음 폴링에 재시도한다.</li>
 * </ul>
 *
 * <h2>판정 기준은 단계다</h2>
 * 설계서 §4.6 이 정한 기준은 "Kafka {@code send()} 이전 단계의 예외와 직렬화 예외는 결정적,
 * 전송·네트워크 예외는 일시적" 이다. 그래서 예외 타입만 보지 않고 {@link Phase} 를 함께 받는다.
 * 같은 {@code IllegalStateException} 이라도 봉투를 만들다 난 것과 전송 중에 난 것은 성질이 다르다.
 *
 * <h2>애매하면 일시적이다</h2>
 * 격리는 사람의 개입을 요구한다. 잘못 격리하면 멀쩡한 이벤트가 멈추고, 잘못 재시도하면
 * 최악의 경우 예전 동작(무한 재시도)으로 돌아갈 뿐이다. 되돌리기 쉬운 쪽이 기본값이다.
 *
 * <p>이 클래스는 프레임워크에 의존하지 않고 상태도 없다. 정책이 한 곳에 모여 있어야
 * "이 예외가 왜 격리되는가" 를 코드 전체에서 재구성하지 않아도 된다.
 */
public final class PublishFailureClassifier {

    /** 실패가 발생한 단계. */
    public enum Phase {

        /** outbox 행 → 봉투 → 레코드. 아직 브로커에 닿지 않았다. */
        ASSEMBLY,

        /** 브로커 전송과 그 결과 대기. */
        DELIVERY
    }

    /** 실패의 성질. */
    public enum Kind {

        /** 재시도해도 같은 결과. 격리한다. */
        DETERMINISTIC,

        /** 기다리면 풀린다. 격리하지 않는다. */
        TRANSIENT
    }

    /**
     * 실패를 판정한다.
     *
     * @param phase   실패가 난 단계
     * @param failure 실패 원인. {@code ExecutionException} 등 래퍼는 벗겨서 본다.
     * @return 결정적이면 {@link Kind#DETERMINISTIC}, 아니면 {@link Kind#TRANSIENT}
     */
    public Kind classify(Phase phase, Throwable failure) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(failure, "failure");

        return switch (phase) {
            // 조립 단계의 실패는 정의상 행 자체의 문제다. 이 단계는 네트워크도 브로커도 건드리지 않고
            // 저장된 바이트만 읽으므로, 같은 행을 다시 읽으면 같은 예외가 난다.
            case ASSEMBLY -> Kind.DETERMINISTIC;
            case DELIVERY -> deliveryKind(unwrap(failure));
        };
    }

    /**
     * 전송 단계는 <strong>Kafka 자신의 재시도 가능 여부</strong>를 따른다.
     *
     * <p>예외 타입을 손으로 나열하지 않는 이유: 그 목록은 반드시 불완전해진다.
     * 빠뜨린 비재시도 예외 하나가 곧바로 head-of-line blocking 으로 돌아오기 때문이다 —
     * 예를 들어 {@code InvalidTopicException}(토픽 이름이 규칙 위반)이나
     * {@code TopicAuthorizationException}(ACL 미부여)은 몇 번을 재시도해도 같은 결과인데,
     * 목록에 없으면 일시적으로 분류돼 그 행이 뒤의 모든 이벤트를 영구히 막는다.
     * 이건 ADR-015 가 없애려던 바로 그 상태다.
     *
     * <p>{@link RetriableException} 은 Kafka 가 "재시도하면 풀릴 수 있다" 고 선언한 것들의 마커다.
     * 우리가 원하는 경계와 정확히 같고, 클라이언트 버전이 올라가며 새 예외가 생겨도 따라온다.
     * 의도했던 예외 처리도 그대로 남는다 — {@code UnknownTopicOrPartitionException} 은
     * {@code RetriableException} 이라 기동 직후 토픽이 아직 없는 상황은 여전히 일시적이다.
     */
    private static Kind deliveryKind(Throwable cause) {
        // Kafka 가 재시도 가능이라고 선언했다. 그 판단을 그대로 쓴다.
        if (cause instanceof RetriableException) {
            return Kind.TRANSIENT;
        }
        // 직렬화 실패는 §4.6 이 명시적으로 결정적이라고 정한 경우다. 같은 값을 다시 직렬화해도 같다.
        // (KafkaException 이지만 ApiException 은 아니라 아래 분기에 걸리지 않는다.)
        if (cause instanceof SerializationException) {
            return Kind.DETERMINISTIC;
        }
        // 브로커가 돌려준 오류인데 재시도 가능이 아니다 = Kafka 가 결정적이라고 말한 것이다.
        if (cause instanceof ApiException) {
            return Kind.DETERMINISTIC;
        }
        // Kafka 가 분류하지 않은 예외(IO, 프로듀서 상태 오류 등)는 판단 근거가 없다.
        // ADR-015 의 "애매하면 일시적" 이 여기에 적용된다.
        return Kind.TRANSIENT;
    }

    /**
     * {@code CompletableFuture} 계열이 씌우는 래퍼를 벗긴다.
     *
     * <p>{@code future.get()} 은 실제 원인을 {@code ExecutionException} 에 싸서 던진다.
     * 벗기지 않으면 모든 전송 실패가 같은 타입으로 보여 판정이 무의미해진다.
     */
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
