package com.dawnline.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.observability.DawnlineMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 무한 재시도의 두 신호 — 카운터(몇 번 · 왜)와 나이(얼마나 오래) (DESIGN.md §9.1, ADR-015 후속 정정 결정 4).
 *
 * <p>나이는 끝이 셋이다: 성공 · DLQ · 파티션이 떠남. 하나라도 빠지면 풀린 뒤에도 게이지가 오른 채로 남아 알림이 거짓으로 운다 —
 * 셋을 하나씩 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ConsumerRetryObserverTest {

    private static final String CONSUMER = "fulfillment-service";
    private static final Exception DB_DOWN = new CannotCreateTransactionException("x",
            new SQLTransientConnectionException("Connection is not available"));

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-25T09:00:00Z"));
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ConsumerRetryObserver observer = new ConsumerRetryObserver(meters, CONSUMER, clock);

    @Test
    void 기동하면_사유_전부가_0_으로_있고_나이는_0_이다() {
        // 전제 — 처음 셀 때 태어나는 시계열은 increase() 가 첫 증가를 읽지 못한다(§9.1).
        assertThat(meters.find(DawnlineMetrics.EVENT_RETRY.meterName()).counters())
                .extracting(counter -> counter.getId().getTag("reason"))
                .containsExactlyInAnyOrderElementsOf(ConsumeFailure.retryReasons());
        assertThat(age()).isZero();
    }

    @Test
    void 실패마다_사유의_카운터가_오른다() {
        observer.failedDelivery(record(0, 7), DB_DOWN, 1);
        observer.failedDelivery(record(0, 7), DB_DOWN, 2);
        observer.failedDelivery(record(1, 3), new IllegalArgumentException("티어"), 1);

        assertThat(retries("db_connection")).isEqualTo(2.0);
        assertThat(retries("argument")).isEqualTo(1.0);
    }

    @Test
    void 나이는_그_레코드의_첫_실패부터_재고_파티션_중_최대다() {
        observer.failedDelivery(record(0, 7), DB_DOWN, 1);
        clock.advance(Duration.ofMinutes(10));
        observer.failedDelivery(record(0, 7), DB_DOWN, 2);   // 같은 레코드 — 시작 시각을 지킨다
        observer.failedDelivery(record(1, 3), DB_DOWN, 1);   // 다른 파티션 — 이제 막 시작
        clock.advance(Duration.ofMinutes(20));

        assertThat(age()).isEqualTo(Duration.ofMinutes(30).toSeconds());
    }

    @Test
    void 그_레코드가_성공하면_나이가_0_으로_돌아간다() {
        observer.failedDelivery(record(0, 7), DB_DOWN, 1);
        clock.advance(Duration.ofMinutes(5));

        observer.success(record(0, 7), null);

        assertThat(age()).isZero();
    }

    @Test
    void DLQ_로_가면_나이가_0_으로_돌아간다() {
        observer.failedDelivery(record(0, 7), new IllegalArgumentException("티어"), 4);
        clock.advance(Duration.ofSeconds(7));

        observer.recovered(record(0, 7), new IllegalArgumentException("티어"));

        assertThat(age()).isZero();
    }

    @Test
    void 파티션이_떠나면_나이가_0_으로_돌아간다() {
        // 다른 인스턴스가 이어 재시도한다 — 그쪽 게이지가 오르고, 여기 남으면 두 곳이 운다.
        observer.failedDelivery(record(0, 7), DB_DOWN, 1);
        observer.failedDelivery(record(1, 3), DB_DOWN, 1);
        clock.advance(Duration.ofMinutes(5));

        observer.onPartitionsRevokedAfterCommit(null, List.of(new TopicPartition(TOPIC, 0)));
        assertThat(age()).as("파티션 1 은 남아 있다").isEqualTo(300.0);

        observer.onPartitionsLost(null, List.of(new TopicPartition(TOPIC, 1)));
        assertThat(age()).isZero();
    }

    @Test
    void 앞선_오프셋의_성공은_막고_있는_레코드를_풀지_않는다() {
        // 되감기 전에 같은 배치의 앞 레코드가 성공한 것 — 막힌 것은 그 뒤다.
        observer.failedDelivery(record(0, 7), DB_DOWN, 1);
        clock.advance(Duration.ofSeconds(30));

        observer.success(record(0, 6), null);

        assertThat(age()).isEqualTo(30.0);
    }

    @Test
    void 다른_레코드가_막기_시작하면_시계를_새로_잰다() {
        observer.failedDelivery(record(0, 7), DB_DOWN, 1);
        clock.advance(Duration.ofMinutes(5));
        observer.recovered(record(0, 7), DB_DOWN);
        observer.failedDelivery(record(0, 8), DB_DOWN, 1);
        clock.advance(Duration.ofSeconds(10));

        assertThat(age()).isEqualTo(10.0);
    }

    private static final String TOPIC = "dawnline.order.placed.v1";

    private static ConsumerRecord<Object, Object> record(int partition, long offset) {
        return new ConsumerRecord<>(TOPIC, partition, offset, "k", "v");
    }

    private double retries(String reason) {
        return meters.get(DawnlineMetrics.EVENT_RETRY.meterName()).tag("consumer", CONSUMER).tag("reason", reason)
                .counter().count();
    }

    private double age() {
        return meters.get(DawnlineMetrics.EVENT_RETRY_AGE_SECONDS.meterName()).tag("consumer", CONSUMER).gauge().value();
    }

    /** 테스트가 앞으로 돌리는 시계. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
