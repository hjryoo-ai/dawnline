package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.error.ValidationException;

/**
 * 두 지점 사이의 이동 (DESIGN.md §6.2).
 *
 * <p>거리와 시간을 함께 돌려주는 이유는 {@link DistanceProvider} 구현마다 둘의 관계가 다르기
 * 때문이다 — 하버사인은 평균 속도로 시간을 만들지만 OSRM 은 도로망에서 직접 받는다. 호출부가
 * 거리에서 시간을 유도하면 그 차이가 사라진다.
 *
 * @param meters  거리(m)
 * @param seconds 소요 시간(초)
 */
public record Travel(int meters, int seconds) {

    /** 같은 지점 — 이동 없음. */
    public static final Travel NONE = new Travel(0, 0);

    public Travel {
        if (meters < 0) {
            throw ValidationException.field("meters", meters, "거리는 음수일 수 없습니다");
        }
        if (seconds < 0) {
            throw ValidationException.field("seconds", seconds, "시간은 음수일 수 없습니다");
        }
    }
}
