package com.dawnline.messaging.idempotency;

import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.MessagingMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 모든 Kafka 리스너가 통과해야 하는 멱등 게이트 (CLAUDE.md 불변규칙 2, DESIGN.md §4.4·§8.5).
 *
 * <h2>순서가 왜 이런가</h2>
 * 트랜잭션 안에서 <strong>먼저</strong> {@code processed_events} 를 선점하고, <strong>그 다음</strong>
 * 비즈니스 로직을 실행한다. 반대로 하면(로직 먼저, 기록 나중) 로직은 성공했는데 기록 직전에 죽는 창이 생기고,
 * 재배달 시 로직이 두 번 실행된다. 선점을 먼저 하면 그 창이 없다 — 죽으면 트랜잭션이 통째로 롤백되어
 * 선점도 함께 사라지므로, 재배달 때 정확히 한 번 실행된다.
 *
 * <p>비즈니스 로직 + {@code processed_events} 기록 + 자기 outbox 기록이 <strong>하나의 트랜잭션</strong>이다(§4.4).
 * 그래서 {@code work} 안에서 {@code OutboxAppender.append} 를 부르면 자동으로 같은 트랜잭션에 들어간다.
 *
 * <h2>결과 세 가지</h2>
 * <ul>
 *   <li>{@link ConsumeOutcome#PROCESSED} — 처음 받았고 끝까지 실행했다.</li>
 *   <li>{@link ConsumeOutcome#DUPLICATE} — 이미 처리했다. 아무것도 하지 않고 커밋한다(오프셋 진행).</li>
 *   <li>{@link ConsumeOutcome#REJECTED} — 비즈니스 규칙 위반({@link EventRejectedException}).
 *       §4.6 대로 DLQ 로 보내지 않고 warn 로그 + 메트릭만 남긴 뒤 커밋한다.</li>
 * </ul>
 *
 * <p>그 외 예외는 <strong>그대로 던져 올린다</strong>. 트랜잭션이 롤백되고(선점도 사라진다)
 * 리스너 컨테이너의 에러 핸들러가 §4.6 의 재시도 → DLQ 경로를 태운다.
 *
 * <p>{@code TransactionTemplate} 을 쓰고 {@code @Transactional} 을 쓰지 않은 이유: 이 클래스는
 * 자동설정이 {@code @Bean} 으로 만드는 플랫폼 컴포넌트다. AOP 프록시가 걸렸는지 여부에 정확성이
 * 의존하면 안 된다. 템플릿은 프록시 없이도 항상 동작하고, 호출자가 이미 트랜잭션 안이면 그것에 참여한다.
 */
public class IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    private final ProcessedEventRepository repository;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;
    private final Clock clock;

    /**
     * @param repository         {@code processed_events} 저장소
     * @param transactionManager 트랜잭션 관리자 (기본 전파 REQUIRED — 호출자 트랜잭션에 참여)
     * @param meters             Micrometer 레지스트리 (§9.1)
     * @param clock              {@code processed_at} 시각 출처 (불변규칙 12)
     */
    public IdempotentConsumer(ProcessedEventRepository repository, PlatformTransactionManager transactionManager,
            MeterRegistry meters, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.meters = Objects.requireNonNull(meters, "meters");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 봉투로 한 번만 실행한다. 리스너에서 쓰기 가장 좋은 형태다 — {@code eventId} 와 {@code eventType} 을
     * 손으로 옮겨 적을 일이 없으므로 잘못 짝지을 수 없다.
     *
     * @param envelope 받은 봉투
     * @param consumer 소비자 이름 (§8.5 의 멱등 키 두 번째 요소). 인스턴스마다 달라지면 안 된다.
     * @param work     비즈니스 로직
     * @return 소비 결과
     */
    public ConsumeOutcome consumeOnce(EventEnvelope<?> envelope, String consumer, Runnable work) {
        Objects.requireNonNull(envelope, "envelope");
        return consumeOnce(envelope.eventId(), envelope.eventType(), consumer, work);
    }

    /**
     * @param eventId   봉투의 {@code eventId} (UUIDv7)
     * @param eventType 메트릭 태그가 될 이벤트 타입
     * @param consumer  소비자 이름
     * @param work      비즈니스 로직
     * @return 소비 결과
     */
    public ConsumeOutcome consumeOnce(UUID eventId, String eventType, String consumer, Runnable work) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(work, "work");

        ConsumeOutcome outcome = transactions.execute(status -> runInTransaction(eventId, consumer, work));
        ConsumeOutcome resolved = outcome == null ? ConsumeOutcome.DUPLICATE : outcome;
        count(consumer, eventType, resolved);
        return resolved;
    }

    /**
     * {@link #consumeOnce(EventEnvelope, String, Runnable)} 의 불리언 축약.
     *
     * @param envelope 받은 봉투
     * @param consumer 소비자 이름
     * @param work     비즈니스 로직
     * @return 비즈니스 로직이 <strong>끝까지 실행됐으면</strong> {@code true}.
     *         중복이거나 거부됐으면 {@code false}.
     */
    public boolean runOnce(EventEnvelope<?> envelope, String consumer, Runnable work) {
        return consumeOnce(envelope, consumer, work) == ConsumeOutcome.PROCESSED;
    }

    /**
     * @param eventId   봉투의 {@code eventId}
     * @param eventType 이벤트 타입
     * @param consumer  소비자 이름
     * @param work      비즈니스 로직
     * @return 비즈니스 로직이 끝까지 실행됐으면 {@code true}
     */
    public boolean runOnce(UUID eventId, String eventType, String consumer, Runnable work) {
        return consumeOnce(eventId, eventType, consumer, work) == ConsumeOutcome.PROCESSED;
    }

    private ConsumeOutcome runInTransaction(UUID eventId, String consumer, Runnable work) {
        if (!repository.markProcessed(eventId, consumer, clock.instant())) {
            // 이미 처리한 이벤트. 커밋해서 오프셋을 진행시킨다.
            return ConsumeOutcome.DUPLICATE;
        }
        try {
            work.run();
        } catch (EventRejectedException e) {
            // §4.6: DLQ 아님. 롤백하지도 않는다 — processed_events 를 남겨야 다시 오지 않는다.
            // 그래서 이 예외는 상태를 바꾸기 "전에" 던져야 한다(EventRejectedException Javadoc 참고).
            log.warn("이벤트를 거부했습니다. eventId={}, consumer={}, reason={}: {}",
                    eventId, consumer, e.reason(), e.getMessage());
            Counter.builder(MessagingMetrics.EVENT_REJECTED)
                    .description("비즈니스 규칙 위반으로 무시한 이벤트 (DLQ 아님)")
                    .tag(MessagingMetrics.TAG_REASON, e.reason())
                    .register(meters)
                    .increment();
            return ConsumeOutcome.REJECTED;
        }
        return ConsumeOutcome.PROCESSED;
    }

    private void count(String consumer, String eventType, ConsumeOutcome outcome) {
        Counter.builder(MessagingMetrics.EVENT_PROCESSED)
                .description("이벤트 소비 결과")
                .tag(MessagingMetrics.TAG_CONSUMER, consumer)
                .tag(MessagingMetrics.TAG_EVENT_TYPE, eventType)
                .tag(MessagingMetrics.TAG_OUTCOME, outcome.tag())
                .register(meters)
                .increment();
    }
}
