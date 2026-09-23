package com.dawnline.ops.domain;

/**
 * 들어온 사실을 칸에 적을지의 판정 (ADR-051 결정 3).
 *
 * <p>셋으로 나누는 이유는 메트릭 때문이다. {@link #HOLD} 와 {@link #STALE} 은 둘 다 「쓰지 않는다」
 * 이지만 앞의 것은 같은 사실을 다시 들은 것이고 뒤의 것은 순서가 뒤집혀 늦게 온 사실이다 —
 * {@code dawnline_event_stale_total} 은 뒤의 것만 센다. 합치면 그 카운터가 순서 역전의 크기가
 * 아니라 중복의 크기까지 섞어 말한다.
 */
public enum Verdict {

    /** 앞으로 간다 — 들어온 값을 적는다. 칸이 비어 있었던 경우도 여기다. */
    ADVANCE,

    /** 이미 같은 값이다 — 적을 것이 없다. 세지 않는다. */
    HOLD,

    /** 이미 지나온 자리다 — 적지 않고 {@code dawnline_event_stale_total} 로 센다. */
    STALE;

    /** 들어온 값을 칸에 적는가. */
    public boolean writes() {
        return this == ADVANCE;
    }
}
