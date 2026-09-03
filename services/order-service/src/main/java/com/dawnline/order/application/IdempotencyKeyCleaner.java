package com.dawnline.order.application;

import com.dawnline.order.application.port.out.IdempotencyRecords;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code idempotency_keys} 보존 정리 — 기본 7일, 일 1회 (DESIGN.md §5.1, ADR-019).
 *
 * <h2>왜 7일인가 — {@code processed_events} 와 방향이 반대다</h2>
 * {@code ProcessedEventCleaner} 의 14일은 "같은 이벤트가 다시 배달될 수 있는 창" 이고, 잘못 지웠을
 * 때의 결과는 <em>같은 이벤트를 한 번 더 처리하는 것</em>이다. 여기는 다르다. 잘못 지운 뒤 같은
 * 멱등 키가 오면 <strong>새 주문이 만들어진다</strong> — 소포가 두 번 가고, 되돌리려면 사람이
 * 개입해야 한다. 대가가 비대칭이라 클라이언트 재시도 창(수 초~수 분)보다 세 자릿수 큰 여유를 둔다.
 *
 * <p>그래서 <strong>보존 기간은 클라이언트와의 계약</strong>이다. 7일이 지난 멱등 키로 같은 요청을
 * 보내면 재생이 아니라 새 주문이 된다(§5.1).
 *
 * <h2>왜 배치마다 트랜잭션을 닫는가</h2>
 * 두 가지 이유가 있고, 두 번째는 측정으로 알게 된 것이다.
 * <ol>
 *   <li>긴 삭제 트랜잭션은 쓰기 경로(주문 접수)와 같은 페이지를 두고 경쟁한다.</li>
 *   <li><strong>커밋해야 인덱스가 값을 한다.</strong> 한 트랜잭션 안에서 배치를 반복하면 지운 인덱스
 *       항목을 죽은 것으로 표시할 수 없어, k번째 배치가 앞선 k×batchSize 개를 다시 걸어야 한다.
 *       측정에서 하루치 정리가 0.47초 대신 11.29초가 걸렸다
 *       (docs/benchmarks/phase1-idempotency-cleanup-index.md §2).</li>
 * </ol>
 *
 * <p>한 실행이 무한정 길어지지 않도록 {@code maxBatchesPerRun} 으로 상한을 둔다. 상한에 걸리면
 * 남은 행은 다음 실행이 이어서 지운다 — 정리는 정확성이 아니라 용량 관리라 밀려도 안전하다.
 * 다만 <em>계속</em> 상한에 걸린다면 정리가 유입을 못 따라간다는 뜻이므로 로그를 남긴다.
 */
public class IdempotencyKeyCleaner {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyCleaner.class);

    private final IdempotencyRecords records;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final int maxBatchesPerRun;

    /**
     * @param records            {@code idempotency_keys} 저장소
     * @param transactionManager 배치마다 새 트랜잭션을 여는 데 쓴다
     * @param clock              기준 시각 (CLAUDE.md 불변규칙 12)
     * @param batchSize          한 트랜잭션에서 지울 최대 행 수
     * @param maxBatchesPerRun   한 번의 실행에서 반복할 최대 배치 수
     */
    public IdempotencyKeyCleaner(IdempotencyRecords records, PlatformTransactionManager transactionManager,
            Clock clock, int batchSize, int maxBatchesPerRun) {
        this.records = Objects.requireNonNull(records, "records");
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.clock = Objects.requireNonNull(clock, "clock");
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
     * 일 1회 정리 (ADR-019).
     *
     * <p>예외를 삼킨다. 정리 실패는 용량 문제지 정확성 문제가 아니므로 다음 실행이 이어받으면 된다.
     */
    @Scheduled(
            fixedDelayString = "${dawnline.order.idempotency.cleanup-interval-ms:86400000}",
            initialDelayString = "${dawnline.order.idempotency.cleanup-initial-delay-ms:600000}")
    public void cleanupExpired() {
        try {
            deleteExpired();
        } catch (RuntimeException e) {
            log.warn("idempotency_keys 정리 실패. 다음 실행에서 이어서 지웁니다.", e);
        }
    }

    /**
     * 만료 행을 배치로 지운다. 스케줄과 무관하게 직접 호출할 수 있다(테스트·운영 수동 실행).
     *
     * <p>기준은 행이 들고 있는 {@code expires_at} 이지 "지금 설정된 보존 기간" 이 아니다.
     * 그래서 보존 기간을 바꿔도 이미 저장된 행의 수명은 변하지 않는다 — 클라이언트에게 한 약속이
     * 설정 변경으로 소급해 짧아지지 않는다.
     *
     * @return 이번 실행에서 삭제된 총 행 수
     */
    public int deleteExpired() {
        Instant now = clock.instant();
        int total = 0;
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            Integer deleted = transactions.execute(status -> records.deleteExpired(now, batchSize));
            int rows = deleted == null ? 0 : deleted;
            total += rows;
            if (rows < batchSize) {
                // 대상이 소진됐다. limit 만큼 못 채웠다는 것이 그 신호다.
                logResult(total, now, false);
                return total;
            }
        }
        logResult(total, now, true);
        return total;
    }

    private void logResult(int total, Instant now, boolean hitCap) {
        if (hitCap) {
            log.warn("idempotency_keys {}건 삭제 (기준 {}). 한 실행 상한({}배치)에 걸렸다 — "
                    + "계속 걸리면 정리가 유입을 못 따라가는 것이다.", total, now, maxBatchesPerRun);
        } else if (total > 0) {
            log.info("idempotency_keys {}건 삭제 (기준 {})", total, now);
        }
    }
}
