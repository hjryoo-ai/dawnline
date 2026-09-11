package com.dawnline.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
    void 바인딩_사라진_leader_키가_남아_있으면_기동에서_실패한다() {
        // ADR-027 후속 정정으로 dawnline.messaging.outbox.leader.* 가 사라졌다. 남은 설정
        // 파일에 그 키가 있으면 *조용히 무시되는* 것이 아니라 기동에서 실패해야 한다 —
        // 없어진 스위치를 켜 두고 켜졌다고 믿는 것이 이 정정이 출발한 자리다.
        //
        // 전제부터 확인한다: 이 어설션은 ignoreUnknownFields=false 위에서만 의미가 있다.
        assertThat(DawnlineMessagingProperties.class.getAnnotation(ConfigurationProperties.class))
                .as("ignoreUnknownFields 가 true 로 돌아가면 아래 어설션은 아무것도 검사하지 않는다")
                .isNotNull()
                .extracting(ConfigurationProperties::ignoreUnknownFields)
                .isEqualTo(false);

        // 그리고 실제 컨텍스트에서 그렇게 되는지 본다 — Binder 를 직접 부르면 애너테이션이
        // 무엇을 하는지가 아니라 테스트가 무엇을 하는지를 보게 된다.
        new ApplicationContextRunner()
                .withUserConfiguration(EnableProperties.class)
                .withPropertyValues("dawnline.messaging.outbox.leader.enabled=false")
                .run(context -> assertThat(context)
                        .as("사라진 키를 들고 있는 설정은 조용히 뜨면 안 된다")
                        .hasFailed());
    }

    @Test
    void 바인딩_아는_키만_있으면_정상_기동한다() {
        // 위 테스트의 짝. 엄격 모드가 *아무거나* 거절하는 것이 아니라는 것을 함께 보여야
        // "모르는 키에서 멈춘다" 가 검사된 것이다.
        new ApplicationContextRunner()
                .withUserConfiguration(EnableProperties.class)
                .withPropertyValues("dawnline.messaging.outbox.batch-size=50")
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(DawnlineMessagingProperties.class)
                        .extracting(properties -> properties.outbox().batchSize())
                        .isEqualTo(50));
    }

    @EnableConfigurationProperties(DawnlineMessagingProperties.class)
    static class EnableProperties {
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
