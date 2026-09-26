package com.dawnline.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dawnline.common.error.CommonErrorCode;
import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.common.error.ValidationException;
import com.dawnline.messaging.support.InMemoryOutboxRepository;
import com.dawnline.messaging.support.MutableClock;
import com.dawnline.messaging.support.TestTransactionManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 격리 조회·재큐의 판정 (DESIGN.md §4.6 「격리 조회·재큐 엔드포인트」). SQL 이 실제로 그 판정을 하는지는
 * {@code OutboxQuarantineIT} 가 PostgreSQL 에서 본다.
 */
class OutboxQuarantineTest {

    private final MutableClock clock = MutableClock.at("2026-09-24T01:00:00Z");
    private final InMemoryOutboxRepository repository = new InMemoryOutboxRepository();
    private final TestTransactionManager transactionManager = new TestTransactionManager();
    private final OutboxQuarantine quarantine = new OutboxQuarantine(repository, transactionManager);

    private final Logger logger = (Logger) LoggerFactory.getLogger(OutboxQuarantine.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void attachLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void list_격리된_행만_격리_시각_순으로_돌려주고_전체_수를_함께_말한다() {
        OutboxEvent later = quarantined(Instant.parse("2026-09-24T00:30:00Z"));
        OutboxEvent earlier = quarantined(Instant.parse("2026-09-24T00:10:00Z"));
        pending();

        QuarantinedOutboxEvents listed = quarantine.list(50);

        assertThat(listed.total()).isEqualTo(2L);
        assertThat(listed.events()).extracting(QuarantinedOutboxEvent::id)
                .containsExactly(earlier.id(), later.id());
        assertThat(listed.events().getFirst().failedAt()).isEqualTo(Instant.parse("2026-09-24T00:10:00Z"));
        assertThat(listed.events().getFirst().publishAttempts()).isEqualTo(1);
    }

    @Test
    void list_limit_에_잘리면_total_이_목록보다_크다() {
        quarantined(Instant.parse("2026-09-24T00:10:00Z"));
        quarantined(Instant.parse("2026-09-24T00:20:00Z"));
        quarantined(Instant.parse("2026-09-24T00:30:00Z"));

        QuarantinedOutboxEvents listed = quarantine.list(2);

        assertThat(listed.events()).hasSize(2);
        assertThat(listed.total()).as("잘렸다는 사실은 total 이 말한다").isEqualTo(3L);
    }

    @Test
    void list_limit_이_범위_밖이면_400_이다() {
        assertThatThrownBy(() -> quarantine.list(0))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((DomainException) e).details().get("field")).isEqualTo("limit");
        assertThatThrownBy(() -> quarantine.list(OutboxQuarantine.MAX_LIMIT + 1))
                .isInstanceOf(ValidationException.class);
        assertThat(quarantine.list(OutboxQuarantine.MAX_LIMIT).events()).as("상한은 포함한다").isEmpty();
    }

    @Test
    void requeue_격리를_풀고_시도_횟수를_0_으로_되돌린다() {
        OutboxEvent row = quarantined(Instant.parse("2026-09-24T00:10:00Z"));

        RequeuedOutboxEvent requeued = quarantine.requeue(row.id());

        assertThat(requeued.id()).isEqualTo(row.id());
        assertThat(requeued.eventType()).isEqualTo("order.placed");
        OutboxEvent after = repository.findById(row.id()).orElseThrow();
        assertThat(after.isQuarantined()).isFalse();
        assertThat(after.isPublished()).isFalse();
        assertThat(after.publishAttempts()).isZero();
        assertThat(repository.countFailed()).isZero();
        assertThat(repository.countUnpublished()).as("풀린 행은 발행 대기다 — 릴레이가 집는다").isEqualTo(1L);
        assertThat(transactionManager.commits()).isEqualTo(1);
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains("격리를 풀었습니다").contains(row.id().toString()));
    }

    @Test
    void requeue_없는_행이면_404_다() {
        UUID missing = UUID.fromString("01a04dad-80da-7f6e-a63a-e91c10350000");

        assertThatThrownBy(() -> quarantine.requeue(missing))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((DomainException) e).errorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
    }

    @Test
    void requeue_발행_대기인_행이면_409_이고_지금_위치가_PENDING_이다() {
        OutboxEvent row = pending();

        assertThatThrownBy(() -> quarantine.requeue(row.id()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(OutboxErrorCode.NOT_QUARANTINED);
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.details()).containsEntry("currentState", "PENDING")
                            .doesNotContainKey("publishedAt");
                });
        assertThat(repository.findById(row.id()).orElseThrow().publishAttempts())
                .as("격리되지 않은 행은 건드리지 않는다").isEqualTo((short) 0);
    }

    @Test
    void requeue_이미_나간_행이면_409_이고_언제_나갔는지_말한다() {
        OutboxEvent row = pending();
        row.recordFailedAttempt();
        Instant publishedAt = Instant.parse("2026-09-24T00:50:00Z");
        row.markPublished(publishedAt);

        assertThatThrownBy(() -> quarantine.requeue(row.id()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.details())
                        .containsEntry("currentState", "PUBLISHED")
                        .containsEntry("publishedAt", publishedAt.toString()));
        assertThat(repository.findById(row.id()).orElseThrow().publishAttempts())
                .as("발행 완료 행의 진단 기록(시도 횟수)을 지우지 않는다").isEqualTo((short) 1);
    }

    @Test
    void requeue_다시_누르면_409_가_앞의_요청이_적용됐다고_말한다() {
        // 감사 UNKNOWN 의 해소 경로 (ADR-015 후속 정정 결정 3). 첫 번째의 응답을 못 받은 운영자가 다시 누른다.
        OutboxEvent row = quarantined(Instant.parse("2026-09-24T00:10:00Z"));
        quarantine.requeue(row.id());

        assertThatThrownBy(() -> quarantine.requeue(row.id()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(OutboxErrorCode.NOT_QUARANTINED);
                    assertThat(e.details()).containsEntry("currentState", "PENDING");
                });
    }

    @Test
    void requeue_커밋에_실패하면_풀었다고_적지_않는다() {
        // 로그는 커밋 뒤에 남긴다. 트랜잭션 안에서 적으면 이 경우에 로그가 거짓을 말한다.
        OutboxEvent row = quarantined(Instant.parse("2026-09-24T00:10:00Z"));
        OutboxQuarantine failingCommit = new OutboxQuarantine(repository, new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus(true);
            }

            @Override
            public void commit(TransactionStatus status) {
                throw new TransactionSystemException("커밋 실패");
            }

            @Override
            public void rollback(TransactionStatus status) {
                // 커밋이 실패한 뒤라 부를 일이 없다.
            }
        });

        assertThatThrownBy(() -> failingCommit.requeue(row.id())).isInstanceOf(TransactionSystemException.class);
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneSatisfy(message -> assertThat(message).contains("격리를 풀었습니다"));
    }

    private OutboxEvent pending() {
        clock.advance(Duration.ofSeconds(1));
        UUID id = UUID.randomUUID();
        OutboxEvent row = new OutboxEvent(id, "Order", UUID.randomUUID(), "order.placed", "dawnline.order.placed.v1",
                id.toString(), "{\"eventType\":\"order.placed\",\"schemaVersion\":\"1\"}", "{}", clock.instant());
        repository.append(row);
        return row;
    }

    private OutboxEvent quarantined(Instant failedAt) {
        OutboxEvent row = pending();
        row.markFailed(failedAt);
        return row;
    }
}
