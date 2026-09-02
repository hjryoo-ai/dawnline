package com.dawnline.messaging.outbox;

import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.outbox.PublishFailureClassifier.Kind;
import com.dawnline.messaging.outbox.PublishFailureClassifier.Phase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * outbox 배치 한 번을 잠그고 발행한 뒤 {@code published_at} 을 기록한다 (DESIGN.md §4.4).
 *
 * <h2>트랜잭션 경계</h2>
 * 배치 하나가 트랜잭션 하나다. 트랜잭션 안에서
 * (1) {@code FOR UPDATE SKIP LOCKED} 로 행을 잠그고 (2) 브로커로 보내고 (3) 성공한 행에
 * {@code published_at} 을 찍는다. 커밋이 실패하면 아무것도 표시되지 않고 다음 폴링에서 <em>다시</em>
 * 발행된다. 즉 at-least-once 이고, 중복은 소비자의 {@code processed_events} 가 흡수한다 (§4.4).
 * Kafka 트랜잭션/EOS 는 쓰지 않는다 (ADR-006).
 *
 * <h2>부분 실패에서 왜 "첫 실패 지점까지만" 커밋하는가</h2>
 * 배치는 {@code created_at} 오름차순이다. 중간 하나가 실패했을 때 그 뒤 성공분까지 발행 완료로
 * 표시하면, 재시도된 앞 이벤트가 뒤 이벤트보다 <strong>늦게</strong> 파티션에 들어갈 수 있다.
 * 같은 키의 순서가 뒤집히면 §4.5 의 순서 보장이 깨진다. 그래서 첫 실패에서 멈추고, 그 이후 행은
 * 실제로 발행됐더라도 미발행으로 남겨 다음 폴링에 다시 보낸다.
 * <strong>중복은 허용하고 순서는 지킨다</strong> — at-least-once 설계에서 옳은 방향의 트레이드오프다.
 *
 * <h2>결정적 실패와 일시적 실패 (§4.6, ADR-015)</h2>
 * 위 "첫 실패에서 멈춘다" 는 <strong>일시적</strong> 실패에만 해당한다. 브로커가 죽었을 때 뒤 행을
 * 건너뛰어 봐야 그것도 실패하고, 기다리면 전부 풀리기 때문이다.
 *
 * <p><strong>결정적</strong> 실패는 반대다. 봉투를 만들 수 없는 행은 몇 번을 다시 읽어도 같은 예외를 낸다.
 * 여기서 멈추면 그 행이 {@code created_at} 순서 맨 앞에 서서 뒤의 <em>모든</em> 이벤트를 영구히 막는다.
 * 그래서 그 행만 격리({@link OutboxEvent#markFailed})하고 <strong>다음 행을 계속 발행한다</strong>.
 * 격리는 §4.5 의 순서 보장을 그 파티션 키에 한해 깨뜨리며, 그것이 "서비스 전체 정지" 보다 낫다는
 * 판단이 ADR-015 다.
 *
 * <p>판정은 {@link PublishFailureClassifier} 한 곳에서 한다. 쓰기 경로의 가드
 * ({@code Topics.requireValidEventType})가 알려진 진입점을 막지만, 가드는 <em>오늘 아는</em> 실패
 * 모드만 막는다. 진행 보장은 릴레이 자체가 져야 하는 성질이다.
 *
 * <h2>{@code publish_attempts} 의 의미 (§4.6)</h2>
 * <strong>그 행에 대해 {@code send} 가 실제로 시도된 횟수</strong>다. 일시적 실패로 배치가 중단되면
 * 시도되지 않은 뒤 행들은 증가하지 않으며, 이는 의도된 의미다 — 이 컬럼은 "이 행에 무슨 일이
 * 있었는가" 를 말하지 "브로커가 언제부터 죽었는가" 를 말하지 않는다. 후자는
 * {@code dawnline_outbox_lag_seconds}·{@code dawnline_outbox_unpublished} 게이지의 몫이다(§9.1).
 * 이 값을 배치 전체에 뿌리면 실제로 브로커에 닿아 본 행과 큐에서 기다리기만 한 행을 구분할 수 없게 된다.
 */
public class OutboxBatchPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxBatchPublisher.class);

    private final OutboxRepository repository;
    private final RecordPublisher publisher;
    private final EventJson json;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String producer;
    private final int batchSize;
    private final Duration sendTimeout;
    private final PublishFailureClassifier classifier = new PublishFailureClassifier();

    /**
     * @param repository   outbox 저장소
     * @param publisher    브로커 전송 포트
     * @param json         이벤트 전용 JSON 코덱
     * @param transactions 배치 트랜잭션
     * @param clock        {@code published_at} 시각 출처 (불변규칙 12)
     * @param producer     봉투의 {@code producer}
     * @param batchSize    한 번에 처리할 최대 행 수 (§4.4 기본 500)
     * @param sendTimeout  전송 결과 대기 시간
     */
    public OutboxBatchPublisher(OutboxRepository repository, RecordPublisher publisher, EventJson json,
            TransactionTemplate transactions, Clock clock, String producer, int batchSize, Duration sendTimeout) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.json = Objects.requireNonNull(json, "json");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.producer = Objects.requireNonNull(producer, "producer");
        this.sendTimeout = Objects.requireNonNull(sendTimeout, "sendTimeout");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize 는 1 이상이어야 합니다: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /**
     * 배치 한 번을 처리한다.
     *
     * @return 이번 호출에서 발행 완료로 표시한 행 수
     */
    public int publishBatch() {
        Integer published = transactions.execute(status -> publishLockedBatch());
        return published == null ? 0 : published;
    }

    private int publishLockedBatch() {
        List<OutboxEvent> batch = repository.lockUnpublishedBatch(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }

        Instant now = clock.instant();
        List<OutboxEvent> inFlight = new ArrayList<>(batch.size());
        List<CompletableFuture<Void>> futures = new ArrayList<>(batch.size());
        dispatch(batch, inFlight, futures, now);
        return awaitAndMark(inFlight, futures, now);
    }

    /**
     * 1단계 — 행을 레코드로 조립해 브로커로 보낸다. 결과는 기다리지 않는다(파이프라이닝).
     *
     * <p>조립 실패는 정의상 결정적이라 그 행만 격리하고 <strong>계속 진행</strong>한다.
     * 전송 시작이 동기적으로 실패하는 경우(프로듀서 버퍼 고갈, 메타데이터 타임아웃)는 대개 일시적이라
     * 거기서 멈춘다 — 판정은 {@link PublishFailureClassifier} 가 한다.
     */
    private void dispatch(List<OutboxEvent> batch, List<OutboxEvent> inFlight,
            List<CompletableFuture<Void>> futures, Instant now) {
        for (OutboxEvent event : batch) {
            OutboxRecord record;
            try {
                record = assemble(event);
            } catch (RuntimeException e) {
                if (quarantine(Phase.ASSEMBLY, event, e, now)) {
                    continue;
                }
                break;
            }

            try {
                futures.add(publisher.publish(record.topic(), record.key(), record.value(), record.headers()));
                inFlight.add(event);
            } catch (RuntimeException e) {
                if (quarantine(Phase.DELIVERY, event, e, now)) {
                    continue;
                }
                break;
            }
        }
    }

    /**
     * 2단계 — 전송 결과를 기다리며 성공한 행에 {@code published_at} 을 찍는다.
     *
     * @return 이번 배치에서 발행 완료로 표시한 행 수
     */
    private int awaitAndMark(List<OutboxEvent> inFlight, List<CompletableFuture<Void>> futures, Instant now) {
        int published = 0;
        for (int i = 0; i < futures.size(); i++) {
            OutboxEvent event = inFlight.get(i);
            try {
                futures.get(i).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("outbox 발행이 중단됐습니다. 남은 행은 다음 폴링에서 재발행합니다. eventId={}", event.id());
                break;
            } catch (Exception e) {
                if (quarantine(Phase.DELIVERY, event, e, now)) {
                    continue;
                }
                break;
            }
            event.markPublished(now);
            published++;
        }
        return published;
    }

    /**
     * 실패를 판정해 기록한다.
     *
     * @return 결정적이라 격리했고 <strong>다음 행을 계속 처리해도 되면</strong> {@code true},
     *         일시적이라 이 배치를 여기서 멈춰야 하면 {@code false}
     */
    private boolean quarantine(Phase phase, OutboxEvent event, Throwable failure, Instant now) {
        if (classifier.classify(phase, failure) == Kind.DETERMINISTIC) {
            // 재시도로 풀리지 않는다. 사람이 행을 고쳐야 한다 → warn 이 아니라 error (§9.4 알림 대상).
            event.markFailed(now);
            log.error("outbox 행을 발행할 수 없어 격리합니다(결정적 실패, {}단계). 뒤의 행은 계속 발행합니다. "
                            + "원인을 고친 뒤 RB-05 절차로 재큐하십시오. eventId={}, topic={}, attempts={}",
                    phase, event.id(), event.topic(), event.publishAttempts(), failure);
            return true;
        }
        // 기다리면 풀린다. 격리하지 않고 그대로 둔다 — 다음 폴링에서 같은 행부터 다시 시도한다.
        event.recordFailedAttempt();
        log.warn("outbox 발행 실패(일시적, {}단계). 이 배치의 나머지는 다음 폴링으로 미룹니다. "
                        + "eventId={}, topic={}, attempts={}",
                phase, event.id(), event.topic(), event.publishAttempts(), failure);
        return false;
    }

    /** 브로커로 보낼 레코드 하나. 조립 단계와 전송 단계를 나누기 위한 값이다. */
    private record OutboxRecord(String topic, String key, String value, Map<String, String> headers) {
    }

    /**
     * outbox 행을 봉투로 되살려 레코드를 만든다 (ASSEMBLY 단계).
     *
     * <p>이 단계는 네트워크도 브로커도 건드리지 않고 저장된 바이트만 읽는다 — 그래서 여기서 나는 실패는
     * 정의상 결정적이다(같은 행을 다시 읽으면 같은 예외).
     *
     * <p>페이로드는 저장된 JSON 을 트리로 읽어 그대로 끼운다. 도메인 타입으로 되돌리지 않는 이유:
     * 릴레이는 페이로드의 의미를 몰라야 하고, 저장 시점의 계약이 발행 시점 코드 변경에 흔들리면 안 된다.
     */
    private OutboxRecord assemble(OutboxEvent event) {
        Map<String, String> headers = readHeaders(event);
        int schemaVersion = schemaVersionOf(headers, event);
        String traceId = EventHeaders.traceIdFrom(headers.get(EventHeaders.TRACEPARENT)).orElse(null);
        JsonNode payload = json.readTree(event.payload());

        EventEnvelope<JsonNode> envelope = new EventEnvelope<>(
                event.id(), event.eventType(), schemaVersion, event.createdAt(),
                producer, event.partitionKey(), traceId, payload);

        return new OutboxRecord(event.topic(), event.partitionKey(), json.write(envelope), headers);
    }

    private Map<String, String> readHeaders(OutboxEvent event) {
        JsonNode node = json.readTree(event.headers());
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            headers.put(property.getKey(), property.getValue().asString());
        }
        return headers;
    }

    private static int schemaVersionOf(Map<String, String> headers, OutboxEvent event) {
        String raw = headers.get(EventHeaders.SCHEMA_VERSION);
        if (raw == null) {
            throw new IllegalStateException("outbox 행에 schemaVersion 헤더가 없습니다: id=" + event.id());
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "schemaVersion 헤더가 정수가 아닙니다: id=%s, value=%s".formatted(event.id(), raw), e);
        }
    }
}
