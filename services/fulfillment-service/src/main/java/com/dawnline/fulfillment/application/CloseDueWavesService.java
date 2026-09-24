package com.dawnline.fulfillment.application;

import com.dawnline.fulfillment.application.port.out.WaveLock;
import com.dawnline.fulfillment.application.port.out.WaveRepository;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.WaveCloseCause;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 컷오프 스케줄러 — 마감할 때가 된 웨이브를 닫는다 (§5.2, ADR-020 결정 2, ADR-025).
 *
 * <h2>마감 시각은 {@code cutoffAt} 이 아니라 {@code cutoffAt + grace} 다</h2>
 * grace(기본 90초)는 outbox 릴레이와 컨슈머 지연을 흡수하는 창이다. 그 안에 도착한 주문은
 * 약속받은 그 웨이브에 그대로 들어간다 — 넘겨 도착하면 다음 웨이브 + {@code promiseRevised} 다.
 *
 * <h2>중복 마감을 막는 세 겹</h2>
 * <ol>
 *   <li><strong>조회 조건</strong> — {@code status='OPEN'} 인 것만 집는다.</li>
 *   <li><strong>Redis 락</strong> — 두 인스턴스가 같은 일을 동시에 <em>시작하지</em> 않게 한다.
 *       낭비를 줄이는 방어이고, Redis 가 죽으면 건너뛴다(불변규칙 7).</li>
 *   <li><strong>{@code FOR UPDATE} + 상태 전이</strong> — 실제 보장이다. 진행 중인 편입이 끝날
 *       때까지 기다린 뒤 배타로 잡고, {@code OPEN} 이 아니면 아무것도 하지 않는다.</li>
 * </ol>
 *
 * <h2>{@code CLOSING} 은 다른 트랜잭션이 볼 수 없다</h2>
 * 한 트랜잭션 안에서 {@code OPEN → CLOSING → CLOSED} 를 모두 지난다. 즉 {@code CLOSING} 은
 * <em>커밋된 적이 없는</em> 중간 단계다. ADR-025 이전에는 이 상태가 "마감 중이니 편입하지 마라"
 * 를 알리는 자리였는데, 이제 그 일은 배타 락이 한다 — 편입은 공유 락에서 기다렸다가
 * {@code CLOSED} 를 본다. 상태를 남겨 둔 이유는 도메인 전이 표가 그 순서를 강제하기 때문이고
 * (건너뛰기가 예외가 된다), 없앨 이유도 딱히 없다.
 *
 * <h2>마감 본문은 운영자 경로와 하나다 (ADR-054 결정 5)</h2>
 * 세 번째 방어부터 outbox 까지는 {@link WaveClosing} 에 있고, 운영자 조기 마감({@link CloseWaveService})도 같은
 * 것을 부른다. 이 클래스에 남은 것은 <em>언제·무엇을</em> 닫는가(조회 조건과 Redis 락)다.
 */
public class CloseDueWavesService {

    private static final Logger log = LoggerFactory.getLogger(CloseDueWavesService.class);

    private final WaveRepository waves;
    private final WaveClosing closing;
    private final WaveLock lock;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration grace;
    private final int batchSize;
    private final FulfillmentMetrics metrics;

    /**
     * @param waves              웨이브 저장소 (마감 대상 조회)
     * @param closing            마감 본문 (운영자 경로와 공유, ADR-054)
     * @param lock               분산 락
     * @param transactionManager 웨이브마다 새 트랜잭션을 여는 데 쓴다
     * @param clock              시각 출처 (불변규칙 12)
     * @param grace              마감 여유 (ADR-020 기본 90초)
     * @param batchSize          한 번의 실행에서 닫을 최대 웨이브 수
     * @param metrics            §9.1 의 웨이브 편입량 게이지
     */
    public CloseDueWavesService(WaveRepository waves, WaveClosing closing, WaveLock lock,
            PlatformTransactionManager transactionManager, Clock clock, Duration grace, int batchSize,
            FulfillmentMetrics metrics) {

        this.waves = Objects.requireNonNull(waves, "waves");
        this.closing = Objects.requireNonNull(closing, "closing");
        this.lock = Objects.requireNonNull(lock, "lock");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "tx"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.grace = Objects.requireNonNull(grace, "grace");
        if (grace.isNegative()) {
            throw new IllegalArgumentException("grace 는 음수일 수 없습니다: " + grace);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize 는 1 이상이어야 합니다: " + batchSize);
        }
        this.batchSize = batchSize;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * 30초마다 돈다 (§5.2).
     *
     * <p>주기가 grace 에 더해지지 않는다 — 스케줄러는 {@code cutoffAt + grace <= now} 인 웨이브를
     * 닫으므로 실제 마감은 그 구간 <em>이후</em>에 떨어진다. 주기는 마감을 늦추는 쪽으로만
     * 작용하므로 예산이 아니라 여유다(ADR-020 결정 2).
     *
     * <p>예외를 삼킨다. 한 번의 실행이 실패해도 다음 주기가 같은 웨이브를 다시 집는다 —
     * 조회 조건이 {@code status='OPEN'} 이라 이미 닫힌 것은 다시 잡히지 않는다.
     */
    @Scheduled(
            fixedDelayString = "${dawnline.fulfillment.wave.close-interval-ms:30000}",
            initialDelayString = "${dawnline.fulfillment.wave.close-initial-delay-ms:10000}")
    public void closeDueWaves() {
        try {
            closeDue();
        } catch (RuntimeException e) {
            log.warn("웨이브 마감 실행 실패. 다음 주기에 다시 시도합니다.", e);
        }
    }

    /**
     * 마감 대상을 닫는다. 스케줄과 무관하게 직접 호출할 수 있다(테스트·운영 수동 실행).
     *
     * @return 이번 실행에서 닫은 웨이브 수
     */
    public int closeDue() {
        Instant threshold = clock.instant().minus(grace);
        List<Wave> due = transactions.execute(status -> waves.findDueForClosing(threshold, batchSize));
        if (due == null || due.isEmpty()) {
            return 0;
        }
        int closed = 0;
        for (Wave wave : due) {
            if (closeOne(wave)) {
                closed++;
            }
        }
        log.info("웨이브 {}건 마감 (대상 {}건, 임계 {})", closed, due.size(), threshold);
        return closed;
    }

    /**
     * 웨이브 하나를 닫는다 — <strong>자기 트랜잭션에서</strong>.
     *
     * <p>하나가 실패해도 나머지는 닫혀야 하고, 마감마다 배타 락을 짧게 쥐어야 하기 때문이다.
     * 한 트랜잭션에 묶으면 첫 웨이브의 편입 대기가 나머지 전부를 막는다.
     */
    private boolean closeOne(Wave candidate) {
        Optional<WaveLock.Guard> guard = lock.tryLock(candidate.id());
        if (guard.isEmpty()) {
            // 다른 인스턴스가 처리 중이다. 정확성은 아래 FOR UPDATE 가 지키므로 그냥 넘어간다.
            return false;
        }
        WaveLock.Guard held = guard.get();
        try {
            WaveClosing.Outcome outcome = transactions.execute(
                    status -> closing.closeIfOpen(candidate.id(), WaveCloseCause.SCHEDULED));
            if (outcome instanceof WaveClosing.Outcome.Closed closed) {
                // 커밋 뒤다. waves.order_count 는 마감 전에 0 이므로(ADR-025) 이 게이지가 편입량의 유일한
                // 관측 경로이고, 마감 시점에 이미 센 값을 그대로 쓴다 — 스크레이프마다 집계하면 관측이
                // §8.2 피크에 부하가 된다.
                metrics.waveClosed(closed.campCode(), closed.wave().serviceTier(), closed.wave().orderCount());
                return true;
            }
            // NotOpen·NotFound: 다른 인스턴스(또는 운영자)가 먼저 닫았다. 세 번째 방어이고, 여기까지 왔다는
            // 것은 락이 새고 있거나 사람이 먼저 눌렀다는 뜻이지만 결과는 안전하다.
            return false;
        } catch (RuntimeException e) {
            log.warn("웨이브 마감 실패. 다음 주기에 다시 시도합니다. waveId={}", candidate.id(), e);
            return false;
        } finally {
            // try-with-resources 를 쓰지 않는 이유: 핸들을 본문에서 참조하지 않아 -Werror 가
            // "쓰이지 않는 자원" 경고를 낸다. 해제 시점은 같다.
            held.close();
        }
    }
}
