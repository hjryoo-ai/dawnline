package com.dawnline.common.archunit.samples.good.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * 규칙 7 을 지키는 표본 — 시계를 주입받아 읽는다 (CLAUDE.md 불변규칙 12).
 *
 * <p>{@code clock.instant()} 는 금지 대상이 아니다. 이것까지 막으면 규칙이 쓸모없어진다 —
 * 시각을 아예 읽을 수 없게 되기 때문이다. 금지하는 것은 <em>어떤 시계를 쓸지 코드가 스스로
 * 정하는 것</em>이지 시각을 읽는 행위가 아니다.
 */
public class InjectedClockUseCase {

    private final Clock clock;

    /**
     * @param clock 주입받은 시계 — 저장 정밀도로 잘린 것이어야 한다
     */
    public InjectedClockUseCase(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @return 주입된 시계가 말하는 지금
     */
    public Instant placedAt() {
        return clock.instant();
    }
}
