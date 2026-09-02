package com.dawnline.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * 설정 기본값이 설계서 숫자와 같은지 확인한다.
 *
 * <p>기본값은 코드에 흩어져 있으면 반드시 문서와 어긋난다. 여기서 DESIGN.md 의 숫자를 한 번에 못박는다.
 */
class DawnlineMessagingPropertiesTest {

    @Test
    void 기본값_설계서의_숫자와_같다() {
        DawnlineMessagingProperties properties = bind(Map.of());

        // §4.4: 폴링 100ms, 배치 500
        assertThat(properties.outbox().enabled()).isTrue();
        assertThat(properties.outbox().batchSize()).isEqualTo(500);
        assertThat(properties.outbox().pollIntervalMs()).isEqualTo(100L);
        // §7.1: 발행 후 7일 지난 행을 배치 삭제
        assertThat(properties.outbox().retention()).isEqualTo(Duration.ofDays(7));

        // §8.3: max.poll.records=100
        assertThat(properties.consumer().maxPollRecords()).isEqualTo(100);

        // §4.6: 200ms·1s·5s 로 3회 재시도 후 <topic>.dlq
        assertThat(properties.retry().initialInterval()).isEqualTo(Duration.ofMillis(200));
        assertThat(properties.retry().multiplier()).isEqualTo(5.0);
        assertThat(properties.retry().maxInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.retry().maxAttempts()).isEqualTo(3);
        assertThat(properties.retry().dlqSuffix()).isEqualTo(".dlq");

        // §4.4: processed_events 보존 14일, 일 1회 배치 삭제
        assertThat(properties.processedEvents().enabled()).isTrue();
        assertThat(properties.processedEvents().retentionDays()).isEqualTo(14);
        assertThat(properties.processedEvents().retention()).isEqualTo(Duration.ofDays(14));
        assertThat(properties.processedEvents().cleanupIntervalMs()).isEqualTo(Duration.ofDays(1).toMillis());

        assertThat(properties.producer()).isNull();
    }

    @Test
    void 보존일수는_토픽_보존_7일보다_길어야_의미가_있다() {
        // §4.4 의 논거 자체를 못박는다. 이 값이 7일 이하로 내려가면 재전달 창을 덮지 못해
        // 같은 이벤트를 두 번 처리할 수 있다 — 숫자만 바꾸고 근거를 안 읽는 변경을 여기서 막는다.
        assertThat(bind(Map.of()).processedEvents().retention())
                .isGreaterThan(Duration.ofDays(7));
    }

    @Test
    void 바인딩_보존일수가_0이면_예외() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DawnlineMessagingProperties.ProcessedEvents(true, 0, 1000, 100, 1L, 1L))
                .withMessageContaining("retention-days");
    }

    @Test
    void 바인딩_정리_배치크기가_0이면_예외() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DawnlineMessagingProperties.ProcessedEvents(true, 14, 0, 100, 1L, 1L))
                .withMessageContaining("batch-size");
    }

    @Test
    void 바인딩_실행당_배치상한이_0이면_예외() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DawnlineMessagingProperties.ProcessedEvents(true, 14, 1000, 0, 1L, 1L))
                .withMessageContaining("max-batches-per-run");
    }

    @Test
    void 바인딩_사용자_설정이_기본값을_이긴다() {
        DawnlineMessagingProperties properties = bind(Map.of(
                "dawnline.messaging.producer", "order-service",
                "dawnline.messaging.outbox.batch-size", "50",
                "dawnline.messaging.retry.max-attempts", "1"));

        assertThat(properties.producer()).isEqualTo("order-service");
        assertThat(properties.outbox().batchSize()).isEqualTo(50);
        assertThat(properties.retry().maxAttempts()).isEqualTo(1);
        // 건드리지 않은 값은 기본값 그대로다.
        assertThat(properties.outbox().retention()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void 바인딩_batchSize가_0이면_예외() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DawnlineMessagingProperties.Outbox(true, 0, Duration.ofSeconds(10),
                        Duration.ofDays(7), 100L, 5000L, 3_600_000L))
                .withMessageContaining("batch-size");
    }

    @Test
    void 바인딩_multiplier가_1미만이면_예외() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DawnlineMessagingProperties.Retry(true, Duration.ofMillis(200), 0.5,
                        Duration.ofSeconds(5), 3, ".dlq"))
                .withMessageContaining("multiplier");
    }

    private static DawnlineMessagingProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("dawnline.messaging", DawnlineMessagingProperties.class);
    }
}
