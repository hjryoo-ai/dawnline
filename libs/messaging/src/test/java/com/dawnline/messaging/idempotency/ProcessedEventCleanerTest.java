package com.dawnline.messaging.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Ids;
import com.dawnline.messaging.support.InMemoryProcessedEventRepository;
import com.dawnline.messaging.support.MutableClock;
import com.dawnline.messaging.support.TestTransactionManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProcessedEventCleaner — processed_events 보존 14일 정리 (DESIGN.md §4.4)")
class ProcessedEventCleanerTest {

    private static final String CONSUMER = "order-service.wave-closed";
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(14);

    private InMemoryProcessedEventRepository repository;
    private TestTransactionManager transactions;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        repository = new InMemoryProcessedEventRepository();
        transactions = new TestTransactionManager();
        clock = MutableClock.at(NOW);
    }

    private ProcessedEventCleaner cleaner(int batchSize, int maxBatchesPerRun) {
        return new ProcessedEventCleaner(repository, transactions, clock, RETENTION, batchSize, maxBatchesPerRun);
    }

    /** 지정한 시각에 처리된 기록 하나를 남기고 그 키를 돌려준다. */
    private String record(Instant processedAt) {
        UUID eventId = Ids.newId();
        repository.markProcessed(eventId, CONSUMER, processedAt);
        return eventId + "|" + CONSUMER;
    }

    @Test
    void 보존_경계보다_오래된_행만_지운다() {
        // 경계는 now - 14일 = 2026-08-19T00:00:00Z.
        String 하루_넘긴_행 = record(NOW.minus(Duration.ofDays(15)));
        String 딱_1초_넘긴_행 = record(NOW.minus(RETENTION).minusSeconds(1));
        String 경계_정각_행 = record(NOW.minus(RETENTION));
        String 안쪽_행 = record(NOW.minus(Duration.ofDays(13)));
        String 방금_행 = record(NOW);

        int deleted = cleaner(1000, 100).deleteExpired();

        assertThat(deleted).isEqualTo(2);
        // 경계 정각은 살아남는다 — SQL 이 `processed_at < :threshold` 라 등호를 포함하지 않는다.
        assertThat(repository.keys()).containsExactlyInAnyOrder(경계_정각_행, 안쪽_행, 방금_행);
        assertThat(repository.keys()).doesNotContain(하루_넘긴_행, 딱_1초_넘긴_행);
    }

    @Test
    void 대상이_없으면_아무것도_지우지_않는다() {
        record(NOW.minus(Duration.ofDays(1)));
        record(NOW);

        assertThat(cleaner(1000, 100).deleteExpired()).isZero();
        assertThat(repository.size()).isEqualTo(2);
    }

    @Test
    void 시간이_흐르면_어제_안전하던_행이_대상이_된다() {
        record(NOW.minus(Duration.ofDays(14)).plusSeconds(1)); // 지금은 경계 안쪽

        assertThat(cleaner(1000, 100).deleteExpired()).isZero();

        clock.advance(Duration.ofSeconds(2));

        assertThat(cleaner(1000, 100).deleteExpired()).isEqualTo(1);
        assertThat(repository.size()).isZero();
    }

    @Test
    void 배치_크기만큼_끊어서_지우고_배치마다_트랜잭션을_닫는다() {
        for (int i = 0; i < 25; i++) {
            record(NOW.minus(Duration.ofDays(20)).plusSeconds(i));
        }

        int deleted = cleaner(10, 100).deleteExpired();

        assertThat(deleted).isEqualTo(25);
        // 10 + 10 + 5. 마지막 배치가 limit 을 못 채운 것이 "대상 소진" 의 신호라 4번째 호출은 없다.
        assertThat(repository.deleteBatchSizes()).containsExactly(10, 10, 5);
        // 락을 오래 잡지 않는다는 설계의 핵심: 배치 하나당 트랜잭션 하나.
        assertThat(transactions.commits()).isEqualTo(3);
        assertThat(transactions.rollbacks()).isZero();
    }

    @Test
    void 대상이_정확히_배치_크기의_배수여도_멈춘다() {
        for (int i = 0; i < 20; i++) {
            record(NOW.minus(Duration.ofDays(20)).plusSeconds(i));
        }

        assertThat(cleaner(10, 100).deleteExpired()).isEqualTo(20);
        // 10, 10 을 지우면 limit 을 채웠으므로 한 번 더 물어본다. 그 호출이 0 을 돌려주며 끝난다.
        assertThat(repository.deleteBatchSizes()).containsExactly(10, 10, 0);
    }

    @Test
    void 한_실행의_상한에_걸리면_남은_행은_다음_실행이_지운다() {
        for (int i = 0; i < 25; i++) {
            record(NOW.minus(Duration.ofDays(20)).plusSeconds(i));
        }

        ProcessedEventCleaner cleaner = cleaner(10, 2);

        assertThat(cleaner.deleteExpired()).isEqualTo(20);
        assertThat(repository.size()).isEqualTo(5);

        assertThat(cleaner.deleteExpired()).isEqualTo(5);
        assertThat(repository.size()).isZero();
    }

    @Test
    void 오래된_행부터_지운다() {
        String 가장_오래된 = record(NOW.minus(Duration.ofDays(40)));
        String 중간 = record(NOW.minus(Duration.ofDays(30)));
        String 가장_최근_만료 = record(NOW.minus(Duration.ofDays(20)));

        // 한 배치에 하나씩만 지우고 상한도 1 이라 정확히 한 행만 사라진다.
        assertThat(cleaner(1, 1).deleteExpired()).isEqualTo(1);

        assertThat(repository.keys()).containsExactlyInAnyOrder(중간, 가장_최근_만료);
        assertThat(repository.keys()).doesNotContain(가장_오래된);
    }

    @Test
    void 스케줄_진입점은_예외를_삼킨다() {
        ProcessedEventRepository exploding = new ProcessedEventRepository() {
            @Override
            public boolean markProcessed(UUID eventId, String consumer, Instant processedAt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isProcessed(UUID eventId, String consumer) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int deleteProcessedBefore(Instant processedAtBefore, int limit) {
                throw new IllegalStateException("DB 연결 없음");
            }
        };
        ProcessedEventCleaner cleaner =
                new ProcessedEventCleaner(exploding, transactions, clock, RETENTION, 10, 5);

        // 정리 실패는 용량 문제지 정확성 문제가 아니다. 다음 실행이 이어받는다.
        cleaner.cleanupExpired();

        // 직접 호출하면 예외가 그대로 올라온다 — 삼키는 것은 스케줄 진입점뿐이다.
        assertThatThrownBy(cleaner::deleteExpired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB 연결 없음");
    }

    @Test
    void 잘못된_설정은_생성_시점에_거부한다() {
        assertThatThrownBy(() -> cleaner(0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
        assertThatThrownBy(() -> cleaner(10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBatchesPerRun");
        assertThatThrownBy(() ->
                new ProcessedEventCleaner(repository, transactions, clock, Duration.ZERO, 10, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retention");
    }
}
