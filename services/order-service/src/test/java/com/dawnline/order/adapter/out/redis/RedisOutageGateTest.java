package com.dawnline.order.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Redis 장애 차단기 (DESIGN.md §7.2, §8.1).
 *
 * <p>시계를 주입하므로 <strong>기다리지 않고</strong> 창의 만료를 검사한다. Resilience4j 의
 * CircuitBreaker 를 쓰지 않은 이유가 이것이다 — 그쪽은 자기 시계로 돌아 실제로 기다려야 한다
 * (불변규칙 12).
 */
@DisplayName("RedisOutageGate — 장애 시 호출 자체를 건너뛴다")
class RedisOutageGateTest {

    private static final Instant START = Instant.parse("2026-09-04T00:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(10);

    /** 테스트가 앞으로 돌릴 수 있는 시계. */
    private static final class MovableClock extends Clock {
        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock(START);
    private final RedisOutageGate gate = new RedisOutageGate(clock, WINDOW);

    @Test
    void 처음에는_건너뛰지_않는다() {
        assertThat(gate.isBypassing()).isFalse();
    }

    @Test
    void 한_번_실패하면_창_동안_건너뛴다() {
        gate.recordFailure();

        assertThat(gate.isBypassing()).isTrue();
        clock.advance(Duration.ofSeconds(9));
        assertThat(gate.isBypassing()).isTrue();
    }

    @Test
    void 창이_지나면_다시_시도한다() {
        // 되돌릴 상태가 없다 — 창이 만료되면 다음 호출이 자연스럽게 탐침이 된다.
        gate.recordFailure();

        clock.advance(WINDOW);

        assertThat(gate.isBypassing()).isFalse();
    }

    @Test
    void 창_경계는_만료_시각_직전까지다() {
        gate.recordFailure();

        clock.advance(WINDOW.minusMillis(1));
        assertThat(gate.isBypassing()).isTrue();
        clock.advance(Duration.ofMillis(1));
        assertThat(gate.isBypassing()).isFalse();
    }

    @Test
    void 동시_실패가_창을_짧게_만들지_않는다() {
        // 창이 막 만료된 순간 여러 요청이 함께 탐침이 되어 실패할 수 있다. 늦은 쪽이 남아야 한다.
        gate.recordFailure();
        clock.advance(Duration.ofSeconds(5));
        gate.recordFailure();

        clock.advance(Duration.ofSeconds(6));   // 첫 창은 이미 지났다
        assertThat(gate.isBypassing()).as("두 번째 실패의 창이 살아 있어야 한다").isTrue();
    }

    @Test
    void 잘못된_인자는_거부한다() {
        assertThatThrownBy(() -> new RedisOutageGate(null, WINDOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RedisOutageGate(clock, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bypassWindow");
        assertThatThrownBy(() -> new RedisOutageGate(clock, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
