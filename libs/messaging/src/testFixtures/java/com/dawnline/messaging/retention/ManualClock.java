package com.dawnline.messaging.retention;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 손으로 옮기는 시계 — 정리기 테스트가 {@link RetentionAges} 의 나이를 재려고 쓴다.
 *
 * <p>정리기의 테스트는 대개 고정 시계를 쓴다. 고정 시계로는 「성공 나이가 자란다」를 볼 수 없다 — 시간이 흐르지
 * 않으면 나이는 늘 0 이고, 그 0 은 「방금 성공했다」와 구별되지 않는다. 그래서 서비스들이 같은 것을 하나씩
 * 만들지 않도록 여기 둔다.
 */
public final class ManualClock extends Clock {

    private final AtomicReference<Instant> now;

    /**
     * @param start 시작 시각
     */
    public ManualClock(Instant start) {
        this.now = new AtomicReference<>(Objects.requireNonNull(start, "start"));
    }

    /**
     * 시계를 앞으로 옮긴다.
     *
     * @param step 옮길 만큼
     */
    public void advance(Duration step) {
        now.updateAndGet(instant -> instant.plus(step));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("UTC 만 쓴다");
    }
}
