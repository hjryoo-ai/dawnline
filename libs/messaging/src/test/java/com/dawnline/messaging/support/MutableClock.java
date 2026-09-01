package com.dawnline.messaging.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 테스트에서 시간을 직접 움직이는 {@link Clock} (CLAUDE.md 불변규칙 12).
 *
 * <p>{@code Clock.fixed} 는 고정만 되고 전진시킬 수 없어서, 릴레이 지연·보관기간처럼
 * "시간이 흐른 뒤" 를 검증하려면 이 구현이 필요하다.
 */
public final class MutableClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    private MutableClock(Instant instant, ZoneId zone) {
        this.instant = Objects.requireNonNull(instant, "instant");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /**
     * @param instant 시작 시각 (UTC)
     */
    public static MutableClock at(Instant instant) {
        return new MutableClock(instant, ZoneId.of("UTC"));
    }

    /**
     * @param iso ISO-8601 순간 표기. 예: {@code 2026-08-29T13:20:11.482Z}
     */
    public static MutableClock at(String iso) {
        return at(Instant.parse(iso));
    }

    /**
     * @param amount 앞으로 이동할 시간
     */
    public MutableClock advance(Duration amount) {
        instant = instant.plus(amount);
        return this;
    }

    /**
     * @param newInstant 새 시각
     */
    public void set(Instant newInstant) {
        instant = Objects.requireNonNull(newInstant, "newInstant");
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
