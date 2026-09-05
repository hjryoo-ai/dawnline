package com.dawnline.messaging.outbox;

import com.dawnline.messaging.outbox.RelayLeadership.State;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * outbox 릴레이 — 폴링 100ms, 배치 500 (DESIGN.md §4.4).
 *
 * <p>발행 자체는 {@link OutboxBatchPublisher} 가 트랜잭션 안에서 한다. 이 클래스는 <em>일정</em>과
 * 두 가지 유지보수 작업(메트릭 갱신·오래된 행 정리)만 담당한다.
 * 발행을 분리한 이유: {@code @Scheduled} 메서드에서 같은 빈의 {@code @Transactional} 메서드를 부르면
 * 프록시를 우회해 트랜잭션이 걸리지 않는 고전적인 함정이 있고, 그 함정은 주석으로 경고하는 것보다
 * 클래스를 나눠 구조적으로 없애는 편이 낫다.
 *
 * <p><strong>서비스당 단일 활성 인스턴스를 {@link RelayLeadership} 이 보장한다</strong>
 * (§4.4, ADR-027). {@code FOR UPDATE SKIP LOCKED} 는 두 인스턴스가 같은 행을 발행하는 것은 막지만,
 * 같은 {@code partition_key} 의 행이 서로 다른 인스턴스에서 나가면 §4.5의 키 단위 순서가 깨진다 —
 * 두 인스턴스의 배치·전송 시점이 다르기 때문이다. 그래서 배치 <em>전에</em> 매번 리더십을 확인하고,
 * 리더가 아니면 발행하지 않는다. {@code SKIP LOCKED} 는 리더 전환 경합의 안전망으로 남는다.
 *
 * <p><strong>락이 거는 것은 발행뿐이다.</strong> 메트릭 갱신과 정리는 리더가 아니어도 돈다 —
 * 이 락이 지키는 것은 <em>순서</em>이고, 순서를 가진 것은 발행밖에 없기 때문이다. 게이지는 인스턴스마다
 * 자기 값을 내야 하고(팔로워의 지연도 보여야 한다), 정리는 어느 인스턴스가 해도 결과가 같다.
 *
 * <p><strong>스케줄러 풀</strong>: 이 클래스의 폴링(100ms)·메트릭(5s)·정리(1h) 와
 * {@code ProcessedEventCleaner} 의 정리(24h)까지 네 작업이 애플리케이션의 {@code TaskScheduler} 를
 * 공유한다. Boot 기본 풀 크기는 1이므로, 릴레이를 켜는 서비스는
 * {@code spring.task.scheduling.pool.size} 를 3 이상으로 두는 것을 권한다 — 두 정리 작업은 배치를
 * 반복하느라 초 단위로 길어질 수 있어서, 겹치는 순간에도 폴링과 메트릭이 자리를 가져야 한다.
 * ({@code fixedDelay} 라 같은 작업이 자기 자신과 겹치지는 않는다.)
 */
public class OutboxRelay implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxBatchPublisher publisher;
    private final OutboxRepository repository;
    private final OutboxMetrics metrics;
    private final RelayLeadership leadership;
    private final TransactionTemplate readOnlyTransactions;
    private final TransactionTemplate writeTransactions;
    private final Clock clock;
    private final Duration retention;

    /**
     * 직전 폴링의 리더십. 로그를 <strong>변화할 때만</strong> 내기 위한 것이다 — 100ms 마다
     * "리더가 아닙니다" 를 찍으면 로그가 사라진다. {@code @Scheduled(fixedDelay)} 라 이 필드를
     * 건드리는 스레드는 한 번에 하나다.
     *
     * <p>{@code null} 로 시작하는 이유: 기동 시점에는 아무것도 모른다. 어떤 값으로 시작하면 그
     * 상태로 뜬 인스턴스가 <em>아무 로그도 남기지 않는다</em> — 리더가 됐다는 사실도, 처음부터
     * 판정 불가라는 사실도.
     */
    @Nullable
    private State lastState;

    /**
     * @param publisher          배치 발행기
     * @param repository         메트릭·정리용 조회
     * @param metrics            게이지 (§9.1)
     * @param leadership         단일 활성 인스턴스 판정 (§4.4, ADR-027)
     * @param transactionManager 유지보수 작업용 트랜잭션 관리자
     * @param clock              정리 기준 시각 (불변규칙 12)
     * @param retention          발행 완료 행 보관 기간 (§7.1 기본 7일)
     */
    public OutboxRelay(OutboxBatchPublisher publisher, OutboxRepository repository, OutboxMetrics metrics,
            RelayLeadership leadership, PlatformTransactionManager transactionManager, Clock clock,
            Duration retention) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.leadership = Objects.requireNonNull(leadership, "leadership");
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention 은 양수여야 합니다: " + retention);
        }

        // 읽기 전용 트랜잭션 — Hibernate 플러시를 막고 커넥션을 read-only 로 둔다.
        // 주의: 이것이 세 값의 *일관된 스냅샷*을 주지는 않는다. PostgreSQL 기본 격리 수준인
        // READ COMMITTED 에서는 문장마다 새 스냅샷을 잡으므로, 세 SELECT 사이에 폴링 스레드가
        // 행을 격리하면 한 스크레이프에서 같은 행이 미발행과 격리 양쪽에 잡힐 수 있다.
        // 5초 뒤 갱신에서 스스로 정정되는 일시적 어긋남이라 격리 수준을 올리지 않았다 —
        // REPEATABLE READ 는 이 주기적 조회에 직렬화 실패 재시도를 새로 들여온다.
        this.readOnlyTransactions = new TransactionTemplate(transactionManager);
        this.readOnlyTransactions.setReadOnly(true);
        // DELETE 는 DML 이라 트랜잭션이 없으면 TransactionRequiredException 이 난다.
        this.writeTransactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 미발행 행을 발행한다 (§4.4 폴링 100ms).
     *
     * <p>예외를 삼킨다. {@code @Scheduled} 는 예외가 나도 다음 실행을 계속하지만, 스택 트레이스가
     * 100ms 마다 쏟아지면 로그를 못 쓰게 된다. Kafka·DB 장애는 outbox 게이지(§9.1)와
     * 알림 규칙(§9.4 "outbox 지연 &gt; 30s")이 잡는다.
     */
    @Scheduled(
            fixedDelayString = "${dawnline.messaging.outbox.poll-interval-ms:100}",
            initialDelayString = "${dawnline.messaging.outbox.initial-delay-ms:1000}")
    public void poll() {
        if (!isLeader()) {
            return;
        }
        try {
            int published = publisher.publishBatch();
            if (published > 0 && log.isDebugEnabled()) {
                log.debug("outbox 발행 완료: {}건", published);
            }
        } catch (RuntimeException e) {
            log.warn("outbox 릴레이 배치 실패. 다음 폴링에서 재시도합니다.", e);
        }
    }

    /**
     * 게이지 갱신 (§9.1). 폴링보다 훨씬 느린 주기로 돌려 DB 부하를 만들지 않는다.
     *
     * <p>세 값을 한 트랜잭션에서 읽지만 격리 수준은 READ COMMITTED 라 완전히 일관된 스냅샷은
     * 아니다(생성자 주석 참고). 한 스크레이프가 어긋나도 다음 갱신에서 정정된다.
     */
    @Scheduled(
            fixedDelayString = "${dawnline.messaging.outbox.metrics-interval-ms:5000}",
            initialDelayString = "${dawnline.messaging.outbox.initial-delay-ms:1000}")
    public void refreshMetrics() {
        try {
            readOnlyTransactions.executeWithoutResult(status -> metrics.refresh(
                    repository.countUnpublished(), repository.unpublishedLagSeconds(), repository.countFailed()));
        } catch (RuntimeException e) {
            log.warn("outbox 메트릭 갱신 실패", e);
        }
    }

    /**
     * 발행된 지 {@code retention} 이 지난 행을 지운다 (§7.1).
     *
     * <p>파티셔닝 대신 삭제를 고른 이유는 설계서에 있다 — 이 테이블은 규모가 작다.
     */
    @Scheduled(
            fixedDelayString = "${dawnline.messaging.outbox.cleanup-interval-ms:3600000}",
            initialDelayString = "${dawnline.messaging.outbox.cleanup-initial-delay-ms:60000}")
    public void cleanupPublished() {
        try {
            Integer deleted = writeTransactions.execute(status ->
                    repository.deletePublishedBefore(clock.instant().minus(retention)));
            if (deleted != null && deleted > 0) {
                log.info("발행 완료 outbox 행 {}건 삭제 (보관기간 {})", deleted, retention);
            }
        } catch (RuntimeException e) {
            log.warn("outbox 정리 실패", e);
        }
    }

    /**
     * 정상 종료 — 리더십을 내려놓아 다음 인스턴스가 TTL 을 기다리지 않게 한다.
     *
     * <p>{@code AutoCloseable} 로 두면 스프링이 빈 소멸 시 {@code close()} 를 부른다(추론된
     * destroy 메서드). {@code @PreDestroy} 를 쓰지 않는 이유는 이 클래스가 애너테이션 처리에
     * 의존하지 않게 하기 위해서다 — 라이브러리 클래스이고, 테스트는 {@code new} 로 만든다.
     */
    @Override
    public void close() {
        leadership.stepDown();
    }

    /**
     * 발행해도 되는가.
     *
     * <p>세 상태를 <strong>다르게 로그한다.</strong> 팔로워는 정상이고(다른 인스턴스가 일하는 중),
     * 판정 불가는 장애다(§9.4 는 결과인 {@code outbox_lag} 로 잡는다). 같은 문장으로 찍으면
     * 대시보드가 아니라 로그를 보는 사람이 그 둘을 구별하지 못한다.
     */
    private boolean isLeader() {
        State state;
        try {
            state = leadership.lead();
        } catch (RuntimeException e) {
            // 구현이 예외를 던지면 판정 불가와 같다. 여기서 발행으로 넘어가면 락이 없는 것과 같다.
            log.warn("리더십 판정이 예외로 끝났습니다. 발행을 멈춥니다.", e);
            state = State.UNKNOWN;
        }
        metrics.leadership(state);

        if (state != lastState) {
            switch (state) {
                case LEADER -> log.info("릴레이 리더가 됐습니다. 발행을 시작합니다.");
                case FOLLOWER -> log.info("다른 인스턴스가 릴레이 리더입니다. 발행하지 않습니다.");
                case UNKNOWN -> log.warn(
                        "릴레이 리더십을 판정할 수 없어 발행을 멈춥니다. 키 단위 순서(§4.5)를 지킬 "
                                + "수단이 DB 에 없어 계속 발행하지 않습니다. outbox 지연이 오릅니다.");
            }
            lastState = state;
        }
        return state == State.LEADER;
    }
}
