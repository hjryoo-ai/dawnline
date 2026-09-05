package com.dawnline.messaging.config;

import com.dawnline.messaging.Topics;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code dawnline.messaging.*} 설정 (DESIGN.md §4.4, §4.6, §8.3).
 *
 * <p>기본값은 전부 설계서에서 온 값이다. 임의로 고른 숫자는 없다.
 *
 * @param producer        봉투의 {@code producer}. 비워 두면 {@code spring.application.name} 을 쓴다.
 * @param outbox          outbox 릴레이 설정
 * @param consumer        소비자 백프레셔 설정
 * @param retry           재시도/DLQ 설정
 * @param processedEvents 멱등 기록 보존 설정
 */
@ConfigurationProperties(prefix = "dawnline.messaging")
public record DawnlineMessagingProperties(
        @Nullable String producer,
        @DefaultValue Outbox outbox,
        @DefaultValue Consumer consumer,
        @DefaultValue Retry retry,
        @DefaultValue ProcessedEvents processedEvents) {

    /**
     * outbox 릴레이 (§4.4: 폴링 100ms, 배치 500).
     *
     * <p>{@code pollIntervalMs}·{@code metricsIntervalMs}·{@code cleanupIntervalMs} 는 코드에서 읽지 않는다.
     * {@code OutboxRelay} 의 {@code @Scheduled} 가 속성 플레이스홀더로 직접 읽기 때문이다.
     * 여기에 선언해 두는 이유는 설정 메타데이터(IDE 자동완성·문서)에 나오게 하기 위해서다.
     * 두 곳의 기본값이 어긋나지 않도록 {@code OutboxRelayScheduleDefaultsTest} 가 검증한다.
     *
     * @param enabled            릴레이 활성화. 릴레이 없이 outbox 기록만 하고 싶은 서비스는 끌 수 있다.
     * @param batchSize          한 배치 최대 행 수 (§4.4)
     * @param sendTimeout        전송 결과 대기 시간
     * @param retention          발행 완료 행 보관 기간 (§7.1)
     * @param pollIntervalMs     폴링 간격(ms) (§4.4)
     * @param metricsIntervalMs  게이지 갱신 간격(ms)
     * @param cleanupIntervalMs  정리 실행 간격(ms)
     * @param leader             릴레이 리더 락 (§4.4, ADR-027)
     */
    public record Outbox(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("500") int batchSize,
            @DefaultValue("10s") Duration sendTimeout,
            @DefaultValue("7d") Duration retention,
            @DefaultValue("100") long pollIntervalMs,
            @DefaultValue("5000") long metricsIntervalMs,
            @DefaultValue("3600000") long cleanupIntervalMs,
            @DefaultValue Leader leader) {

        public Outbox {
            if (batchSize < 1) {
                throw new IllegalArgumentException("dawnline.messaging.outbox.batch-size 는 1 이상이어야 합니다");
            }
            if (sendTimeout.isNegative() || sendTimeout.isZero()) {
                throw new IllegalArgumentException("dawnline.messaging.outbox.send-timeout 은 양수여야 합니다");
            }
            if (retention.isNegative() || retention.isZero()) {
                throw new IllegalArgumentException("dawnline.messaging.outbox.retention 은 양수여야 합니다");
            }
            // 리더십은 배치 *전에* 확인하고 배치 *중에는* 확인하지 않는다. TTL 이 한 배치의 최대
            // 소요(전송 타임아웃)보다 짧으면 배치 도중에 리더가 바뀔 수 있고, 그러면 이 락이
            // 막으려던 바로 그 상황(두 인스턴스의 동시 발행)이 락을 켠 채로 일어난다.
            if (leader.enabled() && leader.ttl().compareTo(sendTimeout) <= 0) {
                throw new IllegalArgumentException(
                        "dawnline.messaging.outbox.leader.ttl(%s)은 send-timeout(%s)보다 길어야 합니다"
                                .formatted(leader.ttl(), sendTimeout));
            }
        }
    }

    /**
     * 릴레이 리더 락 (§4.4, §7.2 {@code lock:relay:{service}}, ADR-027).
     *
     * <p>{@code enabled=false} 는 <strong>"이 배포는 인스턴스가 하나다" 라는 선언</strong>이다.
     * 기본값이 아닌 이유는 그 전제가 조용히 성립하는 것과 적혀 있는 것이 다르기 때문이다 —
     * 스케일아웃하는 사람이 이 줄을 보고 지워야 한다. 켜져 있는데 Redis 가 없으면
     * <strong>기동에서 실패한다</strong>: 없는 락을 있다고 믿는 것이 가장 나쁘다.
     *
     * @param enabled 리더 락 사용 여부
     * @param ttl     리더십 TTL. 리더가 죽으면 이 시간 뒤 다음 인스턴스가 이어받는다.
     *                기본 30초는 §9.4 의 "outbox 지연 &gt; 30s" 알림과 같은 눈금이다 —
     *                교체가 제때 되면 알림이 울리지 않고, 울리면 교체도 안 된 것이다.
     */
    public record Leader(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30s") Duration ttl) {

        public Leader {
            if (ttl.isNegative() || ttl.isZero()) {
                throw new IllegalArgumentException("dawnline.messaging.outbox.leader.ttl 은 양수여야 합니다");
            }
        }
    }

    /**
     * 소비자 백프레셔 (§8.3).
     *
     * @param maxPollRecords 한 번의 poll 로 가져올 최대 레코드 수 (§8.3 기본 100).
     *                       {@code spring.kafka.consumer.max-poll-records} 를 직접 설정하면 그쪽이 이긴다.
     * @param applyMaxPollRecords 위 기본값을 컨슈머 팩토리에 적용할지 여부
     */
    public record Consumer(
            @DefaultValue("100") int maxPollRecords,
            @DefaultValue("true") boolean applyMaxPollRecords) {

        public Consumer {
            if (maxPollRecords < 1) {
                throw new IllegalArgumentException("dawnline.messaging.consumer.max-poll-records 는 1 이상이어야 합니다");
            }
        }
    }

    /**
     * 멱등 기록 보존 (§4.4: 14일, 일 1회 배치 삭제).
     *
     * <p>{@code cleanupIntervalMs}·{@code cleanupInitialDelayMs} 는 코드에서 읽지 않는다.
     * {@code ProcessedEventCleaner} 의 {@code @Scheduled} 가 속성 플레이스홀더로 직접 읽기 때문이다.
     * 여기에 선언해 두는 이유는 설정 메타데이터에 나오게 하기 위해서다 —
     * {@code Outbox} 의 폴링 간격들과 같은 이유이고, 기본값 어긋남은 테스트가 잡는다.
     *
     * <p>보존을 {@code Duration} 이 아니라 <em>일 단위 정수</em>로 받는다. 이 값은 §7.3 의 토픽
     * 보존(7일)에서 유도된 날짜 단위 정책이라, 분·초로 표현할 수 있으면 오히려 오해를 부른다.
     *
     * @param enabled               정리 스케줄러 활성화
     * @param retentionDays         보존 일수 (§4.4 기본 14)
     * @param batchSize             한 트랜잭션에서 지울 최대 행 수
     * @param maxBatchesPerRun      한 번의 실행에서 반복할 최대 배치 수
     * @param cleanupIntervalMs     정리 실행 간격(ms). 기본 24시간 (§4.4 "일 1회")
     * @param cleanupInitialDelayMs 기동 후 첫 실행까지 지연(ms)
     */
    public record ProcessedEvents(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("14") int retentionDays,
            @DefaultValue("1000") int batchSize,
            @DefaultValue("100") int maxBatchesPerRun,
            @DefaultValue("86400000") long cleanupIntervalMs,
            @DefaultValue("300000") long cleanupInitialDelayMs) {

        public ProcessedEvents {
            if (retentionDays < 1) {
                throw new IllegalArgumentException(
                        "dawnline.messaging.processed-events.retention-days 는 1 이상이어야 합니다");
            }
            if (batchSize < 1) {
                throw new IllegalArgumentException(
                        "dawnline.messaging.processed-events.batch-size 는 1 이상이어야 합니다");
            }
            if (maxBatchesPerRun < 1) {
                throw new IllegalArgumentException(
                        "dawnline.messaging.processed-events.max-batches-per-run 은 1 이상이어야 합니다");
            }
        }

        /** 보존 기간. */
        public Duration retention() {
            return Duration.ofDays(retentionDays);
        }
    }

    /**
     * 재시도와 DLQ (§4.6: 200ms·1s·5s 로 3회 재시도 후 {@code <topic>.dlq}).
     *
     * <p>{@code initialInterval=200ms}, {@code multiplier=5.0}, {@code maxAttempts=3} 이면
     * 대기 시간이 정확히 200ms → 1s → 5s 가 된다. 설계서 표를 곱셈 하나로 표현한 것이다.
     *
     * @param enabled         에러 핸들러 등록 여부
     * @param initialInterval 첫 재시도까지 대기 (§4.6)
     * @param multiplier      대기 시간 배수
     * @param maxInterval     대기 시간 상한
     * @param maxAttempts     재시도 횟수(최초 배달 제외). 소진되면 DLQ.
     * @param dlqSuffix       DLQ 토픽 접미사 (§4.6)
     */
    public record Retry(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("200ms") Duration initialInterval,
            @DefaultValue("5.0") double multiplier,
            @DefaultValue("5s") Duration maxInterval,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue(Topics.DLQ_SUFFIX) String dlqSuffix) {

        public Retry {
            if (initialInterval.isNegative() || initialInterval.isZero()) {
                throw new IllegalArgumentException("dawnline.messaging.retry.initial-interval 은 양수여야 합니다");
            }
            if (multiplier < 1.0) {
                throw new IllegalArgumentException("dawnline.messaging.retry.multiplier 는 1.0 이상이어야 합니다");
            }
            if (maxAttempts < 0) {
                throw new IllegalArgumentException("dawnline.messaging.retry.max-attempts 는 0 이상이어야 합니다");
            }
            if (dlqSuffix.isBlank()) {
                throw new IllegalArgumentException("dawnline.messaging.retry.dlq-suffix 는 비어 있을 수 없습니다");
            }
        }
    }
}
