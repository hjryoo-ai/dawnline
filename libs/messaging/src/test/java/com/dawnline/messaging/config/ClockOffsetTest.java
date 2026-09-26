package com.dawnline.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.NestedExceptionUtils;

/**
 * 시뮬레이션은 스케줄이 아니라 시계를 옮긴다 (DESIGN.md §5.6 「시뮬레이션 시계」, ADR-066).
 *
 * <p>셋을 본다 — 오프셋이 시계에 닿는다 · 프로필 {@code sim} 밖에서는 기동하지 않는다(결정 3) · 기동 때 유효 시각 한 줄과 게이지가
 * 난다(결정 2). 기동 거부는 시계 빈을 갈아끼운 구성에서도 선다 — 테스트의 고정 시계가 검사를 조용히 빼지 않는다.
 */
@DisplayName("dawnline.clock.offset — 시계를 옮긴다, 시뮬레이션 프로필에서만")
@ExtendWith(OutputCaptureExtension.class)
class ClockOffsetTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MessagingAutoConfiguration.class))
            .withPropertyValues("spring.application.name=order-service");

    @Test
    void 오프셋은_시뮬레이션_프로필에서_시계를_그만큼_옮긴다() {
        runner.withPropertyValues("dawnline.clock.offset=PT8H", "spring.profiles.active=sim")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Duration ahead = Duration.between(Instant.now(), context.getBean(Clock.class).instant());
                    assertThat(ahead).isBetween(Duration.ofHours(8).minusSeconds(5), Duration.ofHours(8).plusSeconds(5));
                    assertThat(context.getBean(Clock.class).instant().getNano() % 1_000)
                            .as("오프셋을 더해도 저장 정밀도(마이크로초) 그대로다").isZero();
                });
    }

    @Test
    void 시뮬레이션_프로필_없이_오프셋이_있으면_기동하지_않는다() {
        runner.withPropertyValues("dawnline.clock.offset=PT8H", "spring.profiles.active=compose")
                .run(context -> assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("시뮬레이션 프로필"));
    }

    @Test
    void 시계_빈을_갈아끼워도_프로필_없는_오프셋은_기동하지_않는다() {
        runner.withPropertyValues("dawnline.clock.offset=PT8H")
                .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-09-26T13:40:00Z"), ZoneOffset.UTC))
                .run(context -> assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("시뮬레이션 프로필"));
    }

    @Test
    void 오프셋이_없으면_프로필과_무관하게_벽시계로_뜬다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(Duration.between(Instant.now(), context.getBean(Clock.class).instant()).abs())
                    .isLessThan(Duration.ofSeconds(5));
        });
    }

    @Test
    void 기동_때_유효_시각_한_줄과_오프셋_게이지를_낸다(CapturedOutput output) {
        runner.withPropertyValues("dawnline.clock.offset=PT7H12M", "spring.profiles.active=compose,sim")
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(output).contains("시계: 유효 시각").contains("오프셋 PT7H12M");
                    assertThat(context.getBean(MeterRegistry.class).get("dawnline.clock.offset.seconds")
                            .tag("service", "order-service").gauge().value()).isEqualTo(25_920.0);
                });
    }

    @Test
    void 오프셋이_0_이어도_유효_시각_한_줄을_남기고_게이지는_0_이다(CapturedOutput output) {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(output).contains("시계: 유효 시각").contains("벽시계, 오프셋 없음");
                    assertThat(context.getBean(MeterRegistry.class).get("dawnline.clock.offset.seconds")
                            .gauge().value()).isZero();
                });
    }
}
