package com.dawnline.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code dawnline.order.*} 설정 (DESIGN.md §5.1, §7.2, ADR-018·019).
 *
 * @param idempotency 멱등 기록 보존·정리 설정
 * @param rateLimit   고객별 레이트 리밋 (§7.2, §8.3)
 * @param redis       Redis 호출의 지연 예산 (§7.2, §8.1)
 */
@ConfigurationProperties(prefix = "dawnline.order")
public record OrderProperties(
        @DefaultValue Idempotency idempotency,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Redis redis) {

    /**
     * 고객별 레이트 리밋 (§7.2 {@code rl:customer:{id}}).
     *
     * <p>용량 60 · 초당 1 리필은 "정확히 분당 60회" 가 아니라 <strong>분당 60을 넘는 지속 부하를
     * 막되 짧은 버스트는 허용</strong>한다는 뜻이다(§7.2). 오래 쉰 고객은 가득 찬 버킷으로 시작한다.
     *
     * <p>이 값을 끄면(§10) 무인증 API 의 유일한 남용 방지 수단이 사라진다. 끌 수 있게 둔 이유는
     * 통합 테스트·부하 테스트에서 이 축을 빼고 다른 것을 재기 위해서다.
     *
     * @param enabled         레이트 리밋 활성화
     * @param capacity        버킷 용량 (§7.2 기본 60)
     * @param refillPerSecond 초당 리필 개수 (§7.2 기본 1)
     * @param ttlSeconds      버킷 TTL (§7.2 기본 60). 이보다 오래 쉰 고객은 새 버킷으로 시작하는데,
     *                        그 시간이면 어차피 가득 찼을 값이라 결과가 같다
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("60") int capacity,
            @DefaultValue("1") int refillPerSecond,
            @DefaultValue("60") int ttlSeconds) {

        public RateLimit {
            if (capacity < 1) {
                throw new IllegalArgumentException("dawnline.order.rate-limit.capacity 는 1 이상이어야 합니다");
            }
            if (refillPerSecond < 1) {
                throw new IllegalArgumentException(
                        "dawnline.order.rate-limit.refill-per-second 는 1 이상이어야 합니다");
            }
            if (ttlSeconds < 1) {
                throw new IllegalArgumentException("dawnline.order.rate-limit.ttl-seconds 는 1 이상이어야 합니다");
            }
        }
    }

    /**
     * Redis 호출의 지연 예산 (§7.2, §8.1).
     *
     * <p>order-service 의 Redis 사용은 <em>전부</em> 실패해도 안전한 최적화이고(멱등은 DB 폴백,
     * 레이트 리밋은 허용) 둘 다 {@code POST /orders} 핫패스에 있다. 그래서 명령 타임아웃을 하나로
     * 짧게 잡는다 — 기본값(60초)을 두면 Redis 가 <em>멈췄을 때</em> 폴백이 아니라 SLO 파괴가 된다.
     *
     * @param commandTimeoutMs Redis 명령 타임아웃(ms). Lettuce 클라이언트 설정에 적용된다
     * @param outageBypassMs   한 번 실패한 뒤 Redis 호출 자체를 건너뛰는 시간(ms).
     *                         타임아웃만으로는 부족하다 — 500 rps 에서 초당 500번씩 그 시간을 버린다
     */
    public record Redis(
            @DefaultValue("50") long commandTimeoutMs,
            @DefaultValue("10000") long outageBypassMs) {

        public Redis {
            if (commandTimeoutMs < 1) {
                throw new IllegalArgumentException(
                        "dawnline.order.redis.command-timeout-ms 는 1 이상이어야 합니다");
            }
            if (outageBypassMs < 1) {
                throw new IllegalArgumentException(
                        "dawnline.order.redis.outage-bypass-ms 는 1 이상이어야 합니다");
            }
        }

        /** 명령 타임아웃. */
        public Duration commandTimeout() {
            return Duration.ofMillis(commandTimeoutMs);
        }

        /** 장애 시 건너뛰는 창. */
        public Duration outageBypass() {
            return Duration.ofMillis(outageBypassMs);
        }
    }

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
