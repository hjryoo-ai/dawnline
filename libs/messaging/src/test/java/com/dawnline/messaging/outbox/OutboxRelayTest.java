package com.dawnline.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.dawnline.common.Ids;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.support.InMemoryOutboxRepository;
import com.dawnline.messaging.support.MutableClock;
import com.dawnline.messaging.support.RecordingRecordPublisher;
import com.dawnline.messaging.support.TestTransactionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link OutboxRelay} — 일정과 유지보수 작업 (DESIGN.md §4.4, §7.1, §9.1).
 */
class OutboxRelayTest {

    /**
     * 페이로드 예시.
     *
     * @param seq 순번
     */
    record Payload(int seq) {
    }

    private static final Instant NOW = Instant.parse("2026-08-29T13:20:11.482Z");

    private final MutableClock clock = MutableClock.at(NOW);
    private final EventJson json = EventJson.standard();
    private final InMemoryOutboxRepository repository = new InMemoryOutboxRepository(clock);
    private final TestTransactionManager transactionManager = new TestTransactionManager();
    private final OutboxMetrics metrics = new OutboxMetrics(new SimpleMeterRegistry(), "order-service");
    private final RecordingRecordPublisher publisher = RecordingRecordPublisher.alwaysSucceeding();

    @Test
    void poll_배치를_발행한다() {
        append(2);

        relay().poll();

        assertThat(publisher.sent()).hasSize(2);
        assertThat(repository.rows()).allMatch(OutboxEvent::isPublished);
    }

    @Test
    void poll_예외를_삼킨다() {
        // 100ms 마다 스택 트레이스가 쏟아지면 로그를 못 쓰게 된다. 장애는 게이지와 알림이 잡는다 (§9.4).
        OutboxRelay relay = new OutboxRelay(explodingPublisher(), repository, metrics, transactionManager, clock,
                Duration.ofDays(7));

        assertThatNoException().isThrownBy(relay::poll);
    }

    @Test
    void refreshMetrics_미발행_건수와_지연을_갱신한다() {
        append(3);
        clock.advance(Duration.ofSeconds(12));

        relay().refreshMetrics();

        assertThat(metrics.unpublishedCount()).isEqualTo(3);
        assertThat(metrics.lagSeconds()).isEqualTo(12.0);
    }

    @Test
    void refreshMetrics_미발행이_없으면_0이다() {
        append(1);
        OutboxRelay relay = relay();
        relay.poll();

        relay.refreshMetrics();

        assertThat(metrics.unpublishedCount()).isZero();
        assertThat(metrics.lagSeconds()).isZero();
    }

    @Test
    void cleanupPublished_보관기간이_지난_발행완료_행만_지운다() {
        append(2);
        OutboxRelay relay = relay();
        relay.poll();
        clock.advance(Duration.ofDays(8));

        relay.cleanupPublished();

        assertThat(repository.rows()).isEmpty();
    }

    @Test
    void cleanupPublished_보관기간_안의_행은_남긴다() {
        append(2);
        OutboxRelay relay = relay();
        relay.poll();
        clock.advance(Duration.ofDays(6));

        relay.cleanupPublished();

        assertThat(repository.rows()).hasSize(2);
    }

    @Test
    void cleanupPublished_미발행_행은_아무리_오래돼도_지우지_않는다() {
        // 지웠다가는 이벤트가 영영 사라진다. 미발행은 장애의 증거이지 쓰레기가 아니다.
        append(2);
        clock.advance(Duration.ofDays(30));

        relay().cleanupPublished();

        assertThat(repository.rows()).hasSize(2);
    }

    @Test
    void 생성자_보관기간이_0이면_예외() {
        assertThatIllegalArgumentException().isThrownBy(() -> new OutboxRelay(batchPublisher(), repository, metrics,
                transactionManager, clock, Duration.ZERO));
    }

    private OutboxRelay relay() {
        return new OutboxRelay(batchPublisher(), repository, metrics, transactionManager, clock, Duration.ofDays(7));
    }

    private OutboxBatchPublisher batchPublisher() {
        return new OutboxBatchPublisher(repository, publisher, json, new TransactionTemplate(transactionManager),
                clock, "order-service", 500, Duration.ofSeconds(5));
    }

    private OutboxBatchPublisher explodingPublisher() {
        OutboxRepository broken = new OutboxRepository() {
            @Override
            public void append(OutboxEvent event) {
                throw new IllegalStateException("DB 연결 실패");
            }

            @Override
            public List<OutboxEvent> lockUnpublishedBatch(int batchSize) {
                throw new IllegalStateException("DB 연결 실패");
            }

            @Override
            public long countUnpublished() {
                throw new IllegalStateException("DB 연결 실패");
            }

            @Override
            public double unpublishedLagSeconds() {
                throw new IllegalStateException("DB 연결 실패");
            }

            @Override
            public int deletePublishedBefore(Instant publishedBefore) {
                throw new IllegalStateException("DB 연결 실패");
            }
        };
        return new OutboxBatchPublisher(broken, publisher, json, new TransactionTemplate(transactionManager), clock,
                "order-service", 500, Duration.ofSeconds(5));
    }

    private void append(int count) {
        OutboxAppender appender = new OutboxAppender(repository, json, new Ids(clock, new Random(42)), clock,
                "order-service", TraceparentSupplier.NONE);
        for (int i = 0; i < count; i++) {
            appender.append(OutboxMessage.keyedByAggregate("Order", UUID.fromString(
                    "01a04dad-80da-7f6e-a63a-e91c1035%04d".formatted(i)), "order.placed", 1, new Payload(i)));
        }
    }
}
