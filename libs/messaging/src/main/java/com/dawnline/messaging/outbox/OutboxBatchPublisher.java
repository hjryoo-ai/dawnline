package com.dawnline.messaging.outbox;

import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.json.EventJson;
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
 * <h2>봉투를 만들 수 없는 행(독약 행)</h2>
 * 쓰기 경로({@link OutboxMessage} · {@link OutboxAppender})가 봉투와 <em>같은</em> 불변식을 검사하므로
 * 정상 경로로는 이런 행이 생기지 않는다. 그래도 존재한다면(수동 INSERT, 규칙을 조이기 전에 쌓인 과거 행)
 * 그 행 앞까지만 발행하고 멈추며 ERROR 를 남긴다. 재시도로는 풀리지 않는다 —
 * {@code dawnline_outbox_lag_seconds} 알림(§9.4)이 뜨고 사람이 행을 고쳐야 그 뒤가 흐른다.
 * 발행 측 DLQ 는 설계서에 없다(§4.6 의 DLQ 는 <em>소비</em> 측이다).
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

        // 보낼 수 있는 데까지 먼저 보낸다(파이프라이닝). send() 가 *동기적으로* 터지는 경우 —
        // 저장된 행이 봉투 불변식을 어겨 EventEnvelope 를 만들 수 없는 경우 — 는 여기서 잡아 멈춘다.
        // 예외를 밖으로 흘려보내면 트랜잭션이 통째로 롤백돼서 이미 브로커로 나간 앞 행들의
        // published_at 까지 날아가고, 다음 폴링이 같은 배치를 다시 집어 영원히 제자리걸음을 한다.
        // 잡아서 멈추면 최소한 그 행 앞까지는 진행하고, 배치 경계가 독약 행 바로 앞으로 좁혀진다.
        List<CompletableFuture<Void>> futures = new ArrayList<>(batch.size());
        for (OutboxEvent event : batch) {
            try {
                futures.add(send(event));
            } catch (RuntimeException e) {
                // 재시도로 풀리지 않는다. 사람이 행을 고치거나 지워야 그 뒤가 흐른다 → warn 이 아니라 error.
                log.error("outbox 행을 봉투로 만들 수 없습니다. 이 행 앞까지만 발행하고 멈춥니다. "
                                + "행을 고치기 전까지 이 서비스의 outbox 는 더 진행하지 않습니다. eventId={}, topic={}",
                        event.id(), event.topic(), e);
                break;
            }
        }

        Instant publishedAt = clock.instant();
        int published = 0;
        for (int i = 0; i < futures.size(); i++) {
            OutboxEvent event = batch.get(i);
            try {
                futures.get(i).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("outbox 발행이 중단됐습니다. 남은 행은 다음 폴링에서 재발행합니다. eventId={}", event.id());
                break;
            } catch (Exception e) {
                // 여기서 멈춘다(위 Javadoc 의 순서 보장 근거). 남은 행은 미발행으로 남아 재시도된다.
                log.warn("outbox 발행 실패. 이 배치의 나머지는 다음 폴링으로 미룹니다. eventId={}, topic={}",
                        event.id(), event.topic(), e);
                break;
            }
            event.markPublished(publishedAt);
            published++;
        }
        return published;
    }

    /**
     * outbox 행을 봉투로 되살려 보낸다.
     *
     * <p>페이로드는 저장된 JSON 을 트리로 읽어 그대로 끼운다. 도메인 타입으로 되돌리지 않는 이유:
     * 릴레이는 페이로드의 의미를 몰라야 하고, 저장 시점의 계약이 발행 시점 코드 변경에 흔들리면 안 된다.
     */
    private CompletableFuture<Void> send(OutboxEvent event) {
        Map<String, String> headers = readHeaders(event);
        int schemaVersion = schemaVersionOf(headers, event);
        String traceId = EventHeaders.traceIdFrom(headers.get(EventHeaders.TRACEPARENT)).orElse(null);
        JsonNode payload = json.readTree(event.payload());

        EventEnvelope<JsonNode> envelope = new EventEnvelope<>(
                event.id(), event.eventType(), schemaVersion, event.createdAt(),
                producer, event.partitionKey(), traceId, payload);

        return publisher.publish(event.topic(), event.partitionKey(), json.write(envelope), headers);
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
