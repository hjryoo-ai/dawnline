package com.dawnline.order.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.dawnline.order.application.IdempotencyKeyCleaner;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@code @Scheduled} 의 플레이스홀더 기본값과 {@link OrderProperties} 의 기본값이 같은지 본다.
 *
 * <p>이유는 {@code libs/messaging} 의 {@code ScheduledDefaultsTest} 와 같다 — 같은 숫자가 두 곳에
 * 있고(어노테이션 속성은 컴파일 타임 상수여야 한다), 어긋나면 "설정을 바꿨는데 안 먹는" 버그가 되며
 * 그런 버그는 아주 늦게 발견된다.
 */
@DisplayName("@Scheduled 기본값 ↔ dawnline.order 설정 기본값")
class OrderScheduledDefaultsTest {

    /** {@code ${prop.name:default}} 에서 default 를 뽑는다. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[^:}]+:([^}]+)}$");

    /** 이 서비스에서 {@code @Scheduled} 를 가진 클래스 전부. */
    private static final Class<?>[] OWNERS = {IdempotencyKeyCleaner.class};

    private static OrderProperties defaults() {
        return new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("dawnline.order", OrderProperties.class);
    }

    private static Scheduled scheduled(Class<?> owner, String method) {
        try {
            Method target = owner.getMethod(method);
            Scheduled annotation = target.getAnnotation(Scheduled.class);
            assertThat(annotation).as("%s.%s 에 @Scheduled 가 없습니다", owner.getSimpleName(), method).isNotNull();
            return annotation;
        } catch (NoSuchMethodException e) {
            throw new AssertionError(owner.getSimpleName() + "." + method + " 메서드가 없습니다", e);
        }
    }

    private static long defaultOf(String placeholder) {
        Matcher matcher = PLACEHOLDER.matcher(placeholder);
        assertThat(matcher.matches()).as("플레이스홀더에 기본값이 없습니다: %s", placeholder).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    @Test
    void 정리_주기와_초기_지연이_설정_기본값과_같다() {
        Scheduled cleanup = scheduled(IdempotencyKeyCleaner.class, "cleanupExpired");
        OrderProperties.Idempotency idempotency = defaults().idempotency();

        assertThat(defaultOf(cleanup.fixedDelayString())).isEqualTo(idempotency.cleanupIntervalMs());
        assertThat(defaultOf(cleanup.initialDelayString())).isEqualTo(idempotency.cleanupInitialDelayMs());
    }

    @Test
    void 상수가_아니라_속성_플레이스홀더를_쓴다() {
        // 상수를 직접 박아 두면 운영 중에 값을 못 바꾼다.
        Scheduled cleanup = scheduled(IdempotencyKeyCleaner.class, "cleanupExpired");

        assertThat(cleanup.fixedDelayString()).startsWith("${dawnline.order.idempotency.");
        assertThat(cleanup.initialDelayString()).startsWith("${dawnline.order.idempotency.");
    }

    @Test
    void 초기_지연이_processed_events_정리와_겹치지_않는다() {
        // 둘 다 24시간 주기라 첫 실행이 같으면 이후 계속 같이 돈다. 스케줄러 풀에서 서로를 기다린다.
        long processedEventsDefault = 300_000L;   // dawnline.messaging.processed-events.cleanup-initial-delay-ms

        assertThat(defaults().idempotency().cleanupInitialDelayMs()).isNotEqualTo(processedEventsDefault);
    }

    @Test
    void 표에_없는_스케줄_메서드가_없다() {
        List<String> declared = Arrays.stream(OWNERS)
                .flatMap(owner -> Arrays.stream(owner.getMethods())
                        .filter(method -> method.isAnnotationPresent(Scheduled.class))
                        .map(method -> owner.getSimpleName() + "." + method.getName()))
                .sorted()
                .toList();

        assertThat(declared)
                .as("새 @Scheduled 메서드가 생기면 이 테스트와 application.yml 의 스케줄러 풀 주석을 함께 갱신한다")
                .containsExactly("IdempotencyKeyCleaner.cleanupExpired");
    }

    @Test
    void 보존_기본값은_7일이다() {
        // ADR-019 의 클라이언트 계약이다. 바꾸려면 §5.1 문장도 함께 바꿔야 한다.
        assertThat(defaults().idempotency().retentionDays()).isEqualTo(7);
        assertThat(defaults().idempotency().retention()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void 한_실행_배치_상한이_피크_하루치를_덮는다() {
        // §8.1 피크 150,000 주문/일. batchSize 1,000 이면 150배치가 필요하다.
        OrderProperties.Idempotency idempotency = defaults().idempotency();
        long peakOrdersPerDay = 150_000L;

        assertThat((long) idempotency.batchSize() * idempotency.maxBatchesPerRun())
                .as("한 실행이 피크 하루치를 못 지우면 테이블이 조용히 자란다")
                .isGreaterThanOrEqualTo(peakOrdersPerDay);
    }

    @Test
    void 레이트_리밋_기본값이_7_2_표와_같다() {
        // §7.2: 용량 60, 초당 1 리필, TTL 60s. 설계서의 숫자가 코드에만 있으면 둘이 표류한다.
        OrderProperties.RateLimit rateLimit = defaults().rateLimit();

        assertThat(rateLimit.enabled()).isTrue();
        assertThat(rateLimit.capacity()).isEqualTo(60);
        assertThat(rateLimit.refillPerSecond()).isEqualTo(1);
        assertThat(rateLimit.ttlSeconds()).isEqualTo(60);
    }

    @Test
    void Redis_지연_예산_기본값이_SLO_보다_한참_작다() {
        // §8.1 POST /orders p99 200ms. 명령 타임아웃이 그것에 가까우면 폴백이 SLO 를 먹는다.
        OrderProperties.Redis redis = defaults().redis();

        assertThat(redis.commandTimeout()).isEqualTo(Duration.ofMillis(50));
        assertThat(redis.commandTimeout()).isLessThan(Duration.ofMillis(200));
        assertThat(redis.outageBypass()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void 잘못된_설정값은_기동에서_거부된다() {
        assertThat(invalid(0, 1000, 200)).hasMessageContaining("retention-days");
        assertThat(invalid(7, 0, 200)).hasMessageContaining("batch-size");
        assertThat(invalid(7, 1000, 0)).hasMessageContaining("max-batches-per-run");

        assertThat(catchThrowable(() -> new OrderProperties.RateLimit(true, 0, 1, 60)))
                .hasMessageContaining("capacity");
        assertThat(catchThrowable(() -> new OrderProperties.RateLimit(true, 60, 0, 60)))
                .hasMessageContaining("refill-per-second");
        assertThat(catchThrowable(() -> new OrderProperties.RateLimit(true, 60, 1, 0)))
                .hasMessageContaining("ttl-seconds");
        assertThat(catchThrowable(() -> new OrderProperties.Redis(0, 10_000)))
                .hasMessageContaining("command-timeout-ms");
        assertThat(catchThrowable(() -> new OrderProperties.Redis(50, 0)))
                .hasMessageContaining("outage-bypass-ms");
    }

    private static Throwable invalid(int retentionDays, int batchSize, int maxBatches) {
        return catchThrowable(() ->
                new OrderProperties.Idempotency(true, retentionDays, batchSize, maxBatches, 1, 1));
    }
}
