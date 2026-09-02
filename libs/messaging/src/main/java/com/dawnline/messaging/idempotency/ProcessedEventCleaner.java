package com.dawnline.messaging.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code processed_events} 보존 정리 — 기본 14일, 일 1회 (DESIGN.md §4.4, §7.1).
 *
 * <h2>왜 보존 기간이 14일인가</h2>
 *
 * <p>이 테이블은 "같은 이벤트가 다시 배달될 수 있는 동안" 만 필요하다. 그 창의 상한은 본 토픽의
 * {@code retention.ms} 7일이다(§7.3) — 오프셋을 처음으로 되감아도 7일보다 오래된 레코드는 브로커에
 * 남아 있지 않다. 14일은 그 2배 여유다.
 *
 * <p>DLQ 보존 30일은 이 창과 무관하다. DLQ 에 들어간 이벤트는 처리 트랜잭션이 롤백된 것이므로
 * {@code processed_events} 에 성공 기록이 <em>없고</em>, 따라서 replay 의 안전성이 이 테이블에
 * 의존하지 않는다(재처리하면 그때 처음으로 기록된다).
 *
 * <p><strong>경고</strong>: 바로 위 논거는 "성공 처리된 이벤트는 DLQ 에 들어가지 않는다" 는 §4.6의
 * 구조에 의존한다. DLQ 적재 경로를 바꾸는 변경은 이 보존 기간을 다시 계산해야 한다.
 *
 * <h2>왜 한 번에 지우지 않는가</h2>
 *
 * <p>{@code processed_events} 는 <em>모든</em> 리스너가 트랜잭션 안에서 INSERT 하는 경로에 있다.
 * 정리 DELETE 가 큰 범위를 한 트랜잭션으로 잡으면 그동안 소비 경로 전체가 그 락을 기다린다.
 * 그래서 {@code batchSize} 씩 끊어 지우고, <strong>배치마다 트랜잭션을 닫아</strong> 락을 놓는다.
 * 트랜잭션을 하나로 두고 반복하면 배치로 나눈 의미가 사라진다.
 *
 * <p>한 번의 실행이 무한정 길어지지 않도록 {@code maxBatchesPerRun} 으로 상한을 둔다. 상한에 걸리면
 * 남은 행은 다음 실행이 이어서 지운다 — 정리는 정확성이 아니라 용량 관리이므로 밀려도 안전하다.
 */
public class ProcessedEventCleaner {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventCleaner.class);

    private final ProcessedEventRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;
    private final int maxBatchesPerRun;

    /**
     * @param repository         {@code processed_events} 저장소
     * @param transactionManager 배치마다 새 트랜잭션을 여는 데 쓴다
     * @param clock              임계 시각 계산 (CLAUDE.md 불변규칙 12)
     * @param retention          보존 기간 (§4.4 기본 14일)
     * @param batchSize          한 트랜잭션에서 지울 최대 행 수
     * @param maxBatchesPerRun   한 번의 실행에서 반복할 최대 배치 수
     */
    public ProcessedEventCleaner(ProcessedEventRepository repository, PlatformTransactionManager transactionManager,
            Clock clock, Duration retention, int batchSize, int maxBatchesPerRun) {
        this.repository = Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention 은 양수여야 합니다: " + retention);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize 는 1 이상이어야 합니다: " + batchSize);
        }
        if (maxBatchesPerRun < 1) {
            throw new IllegalArgumentException("maxBatchesPerRun 은 1 이상이어야 합니다: " + maxBatchesPerRun);
        }
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
        // DELETE 는 DML 이라 트랜잭션이 없으면 TransactionRequiredException 이 난다.
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 일 1회 정리 (§4.4).
     *
     * <p>예외를 삼킨다. 정리 실패는 용량 문제지 정확성 문제가 아니므로 다음 실행이 이어받으면 된다.
     */
    @Scheduled(
            fixedDelayString = "${dawnline.messaging.processed-events.cleanup-interval-ms:86400000}",
            initialDelayString = "${dawnline.messaging.processed-events.cleanup-initial-delay-ms:300000}")
    public void cleanupExpired() {
        try {
            deleteExpired();
        } catch (RuntimeException e) {
            log.warn("processed_events 정리 실패. 다음 실행에서 이어서 지웁니다.", e);
        }
    }

    /**
     * 만료 행을 배치로 지운다. 스케줄과 무관하게 직접 호출할 수 있다(테스트·운영 수동 실행).
     *
     * @return 이번 실행에서 삭제된 총 행 수
     */
    public int deleteExpired() {
        Instant threshold = clock.instant().minus(retention);
        int total = 0;
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            Integer deleted = transactions.execute(status -> repository.deleteProcessedBefore(threshold, batchSize));
            int rows = deleted == null ? 0 : deleted;
            total += rows;
            if (rows < batchSize) {
                // 대상이 소진됐다. limit 만큼 못 채웠다는 것이 그 신호다.
                logResult(total, threshold, false);
                return total;
            }
        }
        logResult(total, threshold, true);
        return total;
    }

    private void logResult(int total, Instant threshold, boolean hitCap) {
        if (hitCap) {
            log.info("processed_events {}건 삭제 (보관기간 {}, 임계 {}). 한 실행 상한({}배치)에 걸려 "
                    + "남은 행은 다음 실행이 지운다.", total, retention, threshold, maxBatchesPerRun);
        } else if (total > 0) {
            log.info("processed_events {}건 삭제 (보관기간 {}, 임계 {})", total, retention, threshold);
        }
    }
}
