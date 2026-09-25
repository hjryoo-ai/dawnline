package com.dawnline.messaging.kafka;

import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.observability.DawnlineMeters;
import com.dawnline.observability.DawnlineMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.listener.RetryListener;

/**
 * 무한 재시도를 보이게 한다 — 「몇 번 · 왜」와 「얼마나 오래」 (DESIGN.md §9.1, ADR-015 후속 정정 결정 4).
 *
 * <ul>
 *   <li>{@code dawnline_event_retry_total{consumer, reason}} — 재시도 경로에 든 실패한 배달. {@code reason} 은 소비 측 경계표의 행
 *       ({@link ConsumeFailure}). 기동 때 사유 전부를 0 으로 등록한다(§9.1 「없는 시계열은 0 으로 보인다」).</li>
 *   <li>{@code dawnline_event_retry_age_seconds{consumer}} — 지금 재시도 중인 레코드가 파티션을 막고 있는 시간, 파티션 중 최대.
 *       알림은 이 값에 건다. 파티션 정지의 신호는 「몇 번」이 아니라 「얼마나 오래」다.</li>
 * </ul>
 *
 * <h2>나이의 시작과 끝 — 세 역할이 한 객체인 이유</h2>
 * 시작은 에러 핸들러가 안다({@link RetryListener#failedDelivery}). <strong>즉시 DLQ 로 가는 실패도 여기에 한 번 온다</strong> — 재시도
 * 목록 밖의 예외도 Spring Kafka 는 첫 배달의 실패로 알린 뒤 복구기를 부른다(근거: 관측(재현됨) — 2026-09-25 {@code DlqReplayIT}). 그 행
 * ({@link ConsumeFailure#DESERIALIZATION})은 재시도가 아니므로 세지도 재지도 않는다.
 * 끝은 셋이다: 그 레코드가 <strong>성공</strong>했다({@link RecordInterceptor#success} — 에러 핸들러는 성공을 모른다),
 * <strong>DLQ 로 갔다</strong>({@link RetryListener#recovered}), <strong>파티션이 이 인스턴스를 떠났다</strong>(리밸런스 — 다른
 * 인스턴스가 이어 재시도하고 그쪽 게이지가 오른다). 하나라도 빠지면 게이지가 풀린 뒤에도 오른 채로 남는다 — 그러면 알림이 거짓으로
 * 운다(조용히 틀리는 쪽이 아니라 시끄럽게 틀리는 쪽이다).
 *
 * <p>Boot 의 리스너 컨테이너 팩토리가 이 빈을 {@code RecordInterceptor} 와 {@code ConsumerAwareRebalanceListener} 로 집어 간다
 * ({@code getIfUnique}). <strong>서비스가 그 둘 중 하나를 따로 두면</strong> Boot 는 어느 쪽도 꽂지 않고 끝이 셋에서 하나(DLQ)로
 * 준다 — 그때는 이것과 합성해야 한다. {@code ReplayTargetFilter} 의 같은 문단과 같은 조건이다.
 *
 * <h2>파티션마다 한 칸</h2>
 * 실패한 레코드는 그 파티션의 맨 앞에 선다(컨테이너가 그 오프셋으로 되돌린다). 그래서 칸은 파티션마다 하나이고, 같은 오프셋의
 * 거듭된 실패는 처음 시각을 지킨다. 다른 오프셋의 실패는 새 레코드가 막기 시작한 것이다. 동시성이 1 이 아니면 여러 스레드가 서로
 * 다른 파티션을 쓴다 — 표는 스레드 사이에서 공유한다.
 */
public class ConsumerRetryObserver
        implements RetryListener, RecordInterceptor<Object, Object>, ConsumerAwareRebalanceListener {

    /** 막고 있는 레코드 — 오프셋과 그 레코드의 첫 실패 시각. */
    private record Blocked(long offset, Instant since) {
    }

    private static final Logger log = LoggerFactory.getLogger(ConsumerRetryObserver.class);

    private final Map<TopicPartition, Blocked> blocked = new ConcurrentHashMap<>();
    private final MeterRegistry meters;
    private final String consumer;
    private final Clock clock;

    /**
     * @param meters   Micrometer 레지스트리
     * @param consumer {@code consumer} 라벨 값 (보통 서비스 이름 — {@link DlqRecordRecoverer} 와 같다)
     * @param clock    나이의 시계
     */
    public ConsumerRetryObserver(MeterRegistry meters, String consumer, Clock clock) {
        this.meters = Objects.requireNonNull(meters, "meters");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.clock = Objects.requireNonNull(clock, "clock");
        DawnlineMeters.preregister(meters, DawnlineMetrics.EVENT_RETRY, MessagingMetrics.TAG_CONSUMER, consumer);
        DawnlineMeters.gauge(meters, DawnlineMetrics.EVENT_RETRY_AGE_SECONDS, this, ConsumerRetryObserver::ageSeconds,
                MessagingMetrics.TAG_CONSUMER, consumer);
    }

    // --- 시작: 에러 핸들러 ---------------------------------------------------

    @Override
    public void failedDelivery(ConsumerRecord<?, ?> record, Exception failure, int deliveryAttempt) {
        observe("failedDelivery", () -> {
            ConsumeFailure row = ConsumeFailure.of(failure);
            if (row == ConsumeFailure.DESERIALIZATION) {
                return;
            }
            DawnlineMeters.counter(meters, DawnlineMetrics.EVENT_RETRY,
                    MessagingMetrics.TAG_CONSUMER, consumer,
                    MessagingMetrics.TAG_REASON, row.reason())
                    .increment();
            blocked.compute(partitionOf(record), (partition, current) ->
                    current != null && current.offset() == record.offset()
                            ? current
                            : new Blocked(record.offset(), clock.instant()));
        });
    }

    // --- 끝: DLQ · 성공 · 파티션이 떠남 ---------------------------------------

    @Override
    public void recovered(ConsumerRecord<?, ?> record, Exception failure) {
        observe("recovered", () -> release(record));
    }

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
            Consumer<Object, Object> kafkaConsumer) {
        return record;
    }

    @Override
    public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> kafkaConsumer) {
        observe("success", () -> release(record));
    }

    @Override
    public void onPartitionsRevokedAfterCommit(Consumer<?, ?> kafkaConsumer, Collection<TopicPartition> partitions) {
        partitions.forEach(blocked::remove);
    }

    @Override
    public void onPartitionsLost(Consumer<?, ?> kafkaConsumer, Collection<TopicPartition> partitions) {
        partitions.forEach(blocked::remove);
    }

    /**
     * @return 지금 막혀 있는 파티션 중 가장 오래 막힌 시간(초). 막힌 파티션이 없으면 0
     */
    public double ageSeconds() {
        Instant now = clock.instant();
        return blocked.values().stream()
                .mapToDouble(entry -> Duration.between(entry.since(), now).toMillis() / 1000.0)
                .max()
                .orElse(0.0);
    }

    /**
     * 관찰은 처리를 바꾸지 않는다 — 여기서 난 예외가 에러 핸들러로 올라가면 그 레코드의 복구가 막힌다. 처음 판이 그랬다: 닫힌 라벨 밖의
     * 값으로 카운터를 올리다 던진 예외 때문에 즉시 DLQ 로 가야 할 레코드가 DLQ 에 가지 못하고 되풀이됐다(근거: 관측(재현됨) — 2026-09-25
     * {@code DlqReplayIT}). 지표가 틀리는 것은 보이는 결함이고, 처리가 바뀌는 것은 장애다.
     */
    private void observe(String hook, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            log.warn("재시도 관찰에 실패했다 — 처리는 그대로 간다. hook={}", hook, e);
        }
    }

    /** 그 레코드(또는 그 뒤의 레코드)가 끝났으면 칸을 비운다 — 앞선 오프셋의 칸은 이미 지나간 것이다. */
    private void release(ConsumerRecord<?, ?> record) {
        blocked.computeIfPresent(partitionOf(record), (partition, current) ->
                current.offset() <= record.offset() ? null : current);
    }

    private static TopicPartition partitionOf(ConsumerRecord<?, ?> record) {
        return new TopicPartition(record.topic(), record.partition());
    }
}
