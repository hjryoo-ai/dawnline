package com.dawnline.common;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 테스트용 수동 시계. CLAUDE.md 불변규칙 12(시간 주입)를 검증하기 위해 쓴다.
 */
final class MutableClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    MutableClock(Instant instant) {
        this(instant, ZoneOffset.UTC);
    }

    private MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    void advanceMillis(long millis) {
        instant = instant.plusMillis(millis);
    }

    void setInstant(Instant newInstant) {
        instant = newInstant;
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
