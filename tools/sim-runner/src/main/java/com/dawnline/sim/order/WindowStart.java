package com.dawnline.sim.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 창의 시작까지 기다린다 — 유효 시각(주입 시계)으로 (DESIGN.md 부록 A 「창은 하나다」, ADR-066).
 *
 * <h2>왜 주입 시계인가</h2>
 * 창은 컷오프(00:00 KST) 앞의 한 시간이고, 컷오프는 서비스들의 주입 시계로 잰다. 시뮬레이션 스택은 그 시계를 오프셋만큼
 * 옮긴다(`make sim-up`) — sim-runner 도 같은 앵커에서 같은 오프셋을 받으므로, 여기서 읽는 「지금」이 서비스들의 「지금」이다.
 * 벽시계로 기다리면 창은 하루 한 번이다.
 *
 * <h2>기다림에는 상한이 있다</h2>
 * 다음 시작이 {@link #LIMIT} 보다 멀면 기다리지 않고 실패한다. 창을 이미 지났다는 뜻이고(같은 시뮬레이션 스택에서 두 번째로
 * 돌렸다), 그때 할 일은 23시간을 기다리는 것이 아니라 스택을 다시 올리는 것이다 — `make sim-up` 은 이미 적힌 가장 늦은 주문 뒤의
 * 시각을 고른다(ADR-066 결정 6). 창 안에서 늦게 시작하는 것도 하지 않는다 — 한 시간짜리 흐름이 컷오프를 넘는다.
 */
public final class WindowStart {

    /** 기다림의 상한. `make sim-up` 의 기본 유효 시각(22:40)에서 창(22:58)까지는 이 안이다. */
    public static final Duration LIMIT = Duration.ofHours(1);

    /** 컷오프가 사는 시간대(§2.2) — {@code TierSchedule} 과 같다. 함대 단계가 창 뒤의 DAWN 컷오프를 이 시간대로 잰다. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private static final Logger log = LoggerFactory.getLogger(WindowStart.class);

    private final Clock clock;
    private final Sleeper sleeper;

    /**
     * @param clock   주입 시계 — 시뮬레이션이면 오프셋이 더해진 것 (불변규칙 12)
     * @param sleeper 대기
     */
    public WindowStart(Clock clock, Sleeper sleeper) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * 지금 이후 처음 오는 {@code start}(KST)까지 기다린다.
     *
     * @param start 창의 시작
     * @return 기다린 뒤의 유효 시각 — 창이 실제로 열린 시각
     * @throws IllegalStateException 다음 시작이 {@link #LIMIT} 보다 멀 때
     * @throws InterruptedException  대기 중 인터럽트
     */
    public Instant await(LocalTime start) throws InterruptedException {
        Objects.requireNonNull(start, "start");
        Instant now = clock.instant();
        ZonedDateTime local = now.atZone(ZONE);
        ZonedDateTime next = local.with(start);
        if (next.isBefore(local)) {
            next = next.plusDays(1);
        }
        Duration wait = Duration.between(now, next.toInstant());
        if (wait.compareTo(LIMIT) > 0) {
            throw new IllegalStateException(("창의 시작 %s KST 가 %s 뒤다(유효 시각 %s) — 상한 %s 를 넘는다. 창을 이미 지났다: "
                    + "같은 시뮬레이션 스택에서 다시 돌리려면 make sim-down && make sim-up 으로 다음 창 앞에 세운다(ADR-066 결정 6).")
                    .formatted(start, wait, local.toOffsetDateTime(), LIMIT));
        }
        log.info("창 대기: 유효 시각 {} → {} ({})", local.toOffsetDateTime(), next.toOffsetDateTime(), wait);
        sleeper.sleepNanos(wait.toNanos());
        return clock.instant();
    }

    /** @return 지금의 유효 시각 — 창이 언제 닫혔는지를 적을 때 */
    public Instant now() {
        return clock.instant();
    }
}
