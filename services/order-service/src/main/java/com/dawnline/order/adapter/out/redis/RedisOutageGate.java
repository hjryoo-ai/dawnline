package com.dawnline.order.adapter.out.redis;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis 가 아플 때 <strong>호출 자체를 건너뛰는</strong> 차단기 (DESIGN.md §7.2, §8.1).
 *
 * <h2>왜 필요한가 — 폴백과 SLO 는 다른 문제다</h2>
 * §7.2 는 Redis 장애 시의 폴백을 정해 두었다(멱등은 DB, 레이트 리밋은 허용). 그러나 폴백이
 * <em>정확성</em>을 지킨다고 <em>지연</em>까지 지켜지는 것은 아니다. `POST /orders` 의 SLO 는
 * p99 200ms 인데(§8.1), 요청마다 Redis 응답을 기다렸다가 폴백하면 그 기다림이 그대로 p99 에 실린다.
 * <strong>60초 기다린 뒤 "허용" 하는 것은 허용이 아니다.</strong>
 *
 * <p>그래서 두 겹으로 막는다. 명령 타임아웃을 짧게 잡고(50ms), 한 번 실패하면 일정 시간
 * (기본 10초) Redis 를 아예 부르지 않는다. 두 번째가 핵심이다 — 타임아웃만 있으면 500 rps 에서
 * 초당 500번씩 50ms 를 버리지만, 차단기가 있으면 10초에 한 번만 버린다.
 *
 * <h2>왜 Resilience4j 가 아닌가</h2>
 * 확인해 봤다. {@code resilience4j-spring-boot4:2.4.0} 은 해결되지만 {@code resilience4j-spring6}
 * 을 끌고 오고(Spring Framework 6), 무엇보다 CircuitBreaker 가 <strong>자기 시계</strong>로 돈다.
 * 그러면 이 창의 만료를 테스트하려면 실제로 기다려야 하고, 그것은 불변규칙 12(시간은 주입)가
 * 막으려던 바로 그 상황이다. 여기 필요한 것은 {@link AtomicLong} 하나이므로 직접 만든다.
 * 슬라이딩 윈도우·half-open·실패율 임계값이 필요해지면 그때 바꾼다.
 *
 * <h2>성공을 기록하지 않는 이유</h2>
 * 차단 중에는 Redis 를 부르지 않으므로 성공이 일어나지 않는다. 창이 만료되면 다음 호출이 자연스럽게
 * 탐침(probe)이 되고, 성공하면 그대로 진행한다. 되돌릴 상태가 없어서 {@code recordSuccess} 가 없다.
 */
public class RedisOutageGate {

    private static final Logger log = LoggerFactory.getLogger(RedisOutageGate.class);

    private final Clock clock;
    private final Duration bypassWindow;

    /** 이 시각(epoch milli) 전까지는 Redis 를 부르지 않는다. */
    private final AtomicLong bypassUntilEpochMilli = new AtomicLong(Long.MIN_VALUE);

    /**
     * @param clock        시각 출처 (불변규칙 12). 창의 만료를 테스트로 재현할 수 있게 한다
     * @param bypassWindow 한 번 실패한 뒤 Redis 를 건너뛰는 시간
     */
    public RedisOutageGate(Clock clock, Duration bypassWindow) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.bypassWindow = Objects.requireNonNull(bypassWindow, "bypassWindow");
        if (bypassWindow.isNegative() || bypassWindow.isZero()) {
            throw new IllegalArgumentException("bypassWindow 는 양수여야 합니다: " + bypassWindow);
        }
    }

    /** 지금 Redis 를 건너뛰어야 하는가. */
    public boolean isBypassing() {
        return clock.millis() < bypassUntilEpochMilli.get();
    }

    /**
     * Redis 호출이 실패했다. 지금부터 창이 끝날 때까지 건너뛴다.
     *
     * <p>차단 중에는 아무도 Redis 를 부르지 않으므로 이 메서드가 창 안에서 불릴 일은 사실상 없다.
     * 예외는 창이 막 만료된 순간 여러 요청이 동시에 탐침이 되어 함께 실패하는 경우인데, 그때는
     * 가장 늦은 만료가 남는다({@code max}) — 경합해도 창이 짧아지지 않는다.
     */
    public void recordFailure() {
        long until = clock.millis() + bypassWindow.toMillis();
        long previous = bypassUntilEpochMilli.getAndAccumulate(until, Math::max);
        if (previous < clock.millis()) {
            log.warn("Redis 호출에 실패해 {} 동안 건너뜁니다. 그동안 멱등 캐시는 DB 로, "
                    + "레이트 리밋은 허용으로 동작합니다 (§7.2).", bypassWindow);
        }
    }
}
