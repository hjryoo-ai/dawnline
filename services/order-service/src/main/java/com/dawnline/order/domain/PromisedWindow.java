package com.dawnline.order.domain;

import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.ValidationException;
import java.time.Instant;
import java.util.Objects;

/**
 * 약속 배송창 (DESIGN.md §2.2, §4.3).
 *
 * <p>{@link TimeWindow} 를 감싼다. 값 자체는 같지만 도메인에서의 의미가 다르다 — 차량 근무창이나
 * 웨이브 컷오프 구간도 {@code TimeWindow} 이고, 그 셋을 같은 타입으로 두면 인자 순서를 바꿔 넣는
 * 실수를 컴파일러가 못 잡는다. 정시율(§8.1)의 기준이 되는 창이라 특히 그렇다.
 *
 * @param window 시작(포함)–종료(제외)
 */
public record PromisedWindow(TimeWindow window) {

    public PromisedWindow {
        Objects.requireNonNull(window, "window");
    }

    /**
     * 티어 규칙(§2.2)에 맞는 창인지 확인하며 만든다.
     *
     * @param start 시작
     * @param end   종료
     * @param tier  서비스 티어
     */
    public static PromisedWindow of(Instant start, Instant end, ServiceTier tier) {
        Objects.requireNonNull(tier, "tier");
        TimeWindow window = new TimeWindow(start, end);
        if (window.duration().compareTo(tier.maxWindowLength()) > 0) {
            throw ValidationException.field("promisedWindow", window.duration(),
                    tier + " 티어의 배송창은 " + tier.maxWindowLength() + " 이하여야 합니다");
        }
        return new PromisedWindow(window);
    }

    /** 시작(포함). */
    public Instant start() {
        return window.start();
    }

    /** 종료(제외). */
    public Instant end() {
        return window.end();
    }

    /**
     * 이 시각에 배달됐다면 정시인가 (§8.1 정시율).
     *
     * @param deliveredAt 실제 배달 시각
     */
    public boolean isOnTime(Instant deliveredAt) {
        return window.contains(deliveredAt);
    }

    /**
     * 약속창 종료보다 얼마나 늦었는지(분). 정시면 0.
     *
     * @param deliveredAt 실제 배달 시각
     */
    public long minutesLate(Instant deliveredAt) {
        return window.minutesLateFor(deliveredAt);
    }
}
