package com.dawnline.messaging.support;

import com.dawnline.messaging.outbox.OutboxEvent;
import com.dawnline.messaging.outbox.OutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 단위 테스트용 {@link OutboxRepository}.
 *
 * <p>{@code SKIP LOCKED} 같은 동시성 의미는 흉내 내지 않는다. 그건 통합 테스트
 * ({@code OutboxRelayIT})가 실제 PostgreSQL 로 확인할 몫이고, 여기서는 배치 크기·정렬·
 * 부분 실패 처리 같은 <em>로직</em>만 본다.
 */
public final class InMemoryOutboxRepository implements OutboxRepository {

    private final List<OutboxEvent> rows = new ArrayList<>();
    private final Clock clock;
    private int lockCalls;

    /**
     * @param clock 지연 계산 기준 시각
     */
    public InMemoryOutboxRepository(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void append(OutboxEvent event) {
        rows.add(event);
    }

    @Override
    public List<OutboxEvent> lockUnpublishedBatch(int batchSize) {
        lockCalls++;
        // 격리 행은 집지 않는다 — JpaOutboxRepository 의 `failed_at IS NULL` 과 같은 규칙 (§4.6).
        return rows.stream()
                .filter(row -> !row.isPublished() && !row.isQuarantined())
                .sorted(Comparator.comparing(OutboxEvent::createdAt).thenComparing(OutboxEvent::id))
                .limit(batchSize)
                .toList();
    }

    @Override
    public long countUnpublished() {
        return rows.stream().filter(row -> !row.isPublished() && !row.isQuarantined()).count();
    }

    @Override
    public long countFailed() {
        return rows.stream().filter(OutboxEvent::isQuarantined).count();
    }

    @Override
    public double unpublishedLagSeconds() {
        return rows.stream()
                .filter(row -> !row.isPublished() && !row.isQuarantined())
                .map(OutboxEvent::createdAt)
                .min(Comparator.naturalOrder())
                .map(oldest -> Duration.between(oldest, clock.instant()).toMillis() / 1000.0)
                .orElse(0.0);
    }

    @Override
    public int deletePublishedBefore(Instant publishedBefore) {
        List<OutboxEvent> doomed = rows.stream()
                .filter(row -> row.publishedAt().filter(at -> at.isBefore(publishedBefore)).isPresent())
                .toList();
        rows.removeAll(doomed);
        return doomed.size();
    }

    /** 저장된 모든 행(발행 여부 무관). */
    public List<OutboxEvent> rows() {
        return List.copyOf(rows);
    }

    /** {@link #lockUnpublishedBatch} 호출 횟수. */
    public int lockCalls() {
        return lockCalls;
    }
}
