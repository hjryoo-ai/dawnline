package com.dawnline.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code dawnline.order.*} 설정 (DESIGN.md §5.1, ADR-018·019).
 *
 * @param idempotency 멱등 기록 보존·정리 설정
 */
@ConfigurationProperties(prefix = "dawnline.order")
public record OrderProperties(@DefaultValue Idempotency idempotency) {

    /**
     * 멱등 기록 (ADR-019).
     *
     * <p>{@code cleanupIntervalMs}·{@code cleanupInitialDelayMs} 는 코드에서 읽지 않는다.
     * {@code IdempotencyKeyCleaner} 의 {@code @Scheduled} 가 속성 플레이스홀더로 직접 읽는다.
     * 여기 선언해 두는 이유는 설정 메타데이터(IDE 자동완성·문서)에 나오게 하기 위해서이며,
     * 두 곳의 기본값이 어긋나지 않도록 {@code OrderScheduledDefaultsTest} 가 검증한다.
     *
     * <p>보존을 {@code Duration} 이 아니라 <em>일 단위 정수</em>로 받는다. 이 값은 클라이언트와의
     * 계약("7일 지난 키는 새 요청")이라 날짜 단위로 말해야 뜻이 분명하다.
     *
     * @param cleanupEnabled        정리 스케줄러 활성화
     * @param retentionDays         보존 일수 (ADR-019 기본 7)
     * @param batchSize             한 트랜잭션에서 지울 최대 행 수
     * @param maxBatchesPerRun      한 번의 실행에서 반복할 최대 배치 수.
     *                              기본 200 은 §8.1 피크(150,000 주문/일)의 하루치를 1,000행씩
     *                              지우는 데 필요한 150배치에 여유를 더한 값이다
     * @param cleanupIntervalMs     정리 실행 간격(ms). 기본 24시간
     * @param cleanupInitialDelayMs 기동 후 첫 실행까지 지연(ms). {@code processed_events} 정리(5분)와
     *                              겹치지 않게 10분으로 둔다 — 두 정리가 같은 순간에 시작하면
     *                              스케줄러 풀에서 서로를 기다린다
     */
    public record Idempotency(
            @DefaultValue("true") boolean cleanupEnabled,
            @DefaultValue("7") int retentionDays,
            @DefaultValue("1000") int batchSize,
            @DefaultValue("200") int maxBatchesPerRun,
            @DefaultValue("86400000") long cleanupIntervalMs,
            @DefaultValue("600000") long cleanupInitialDelayMs) {

        public Idempotency {
            if (retentionDays < 1) {
                throw new IllegalArgumentException("dawnline.order.idempotency.retention-days 는 1 이상이어야 합니다");
            }
            if (batchSize < 1) {
                throw new IllegalArgumentException("dawnline.order.idempotency.batch-size 는 1 이상이어야 합니다");
            }
            if (maxBatchesPerRun < 1) {
                throw new IllegalArgumentException(
                        "dawnline.order.idempotency.max-batches-per-run 은 1 이상이어야 합니다");
            }
        }

        /** 보존 기간. {@code expires_at = created_at + 이 값} 이다. */
        public Duration retention() {
            return Duration.ofDays(retentionDays);
        }
    }
}
