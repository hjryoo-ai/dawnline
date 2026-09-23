package com.dawnline.ops.application.port.out;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 한 행에 쓸 칸들 — <strong>이 이벤트가 실제로 싣고 있는 칸만</strong> (ADR-051 결정 2).
 *
 * <p>이 클래스는 {@code null} 을 받지 않는다. 그것이 이 클래스의 요점이다 — 「부재는 값이 아니다」
 * 를 리뷰가 아니라 타입이 지킨다. 모르는 칸은 패치에 없고, 패치에 없는 칸은 어댑터가 {@code SET}
 * 에 적지 않는다. 표 전체를 {@code EXCLUDED} 로 덮는 한 줄짜리 정리(ADR-051 기각 (7) 의 「가장
 * 먼저 들어오는 정리」)가 들어올 자리가 없다.
 *
 * @param <C> 표의 칸 enum
 */
public final class Patch<C extends Enum<C>> {

    private final Map<C, Write> writes;

    private Patch(Class<C> columns) {
        this.writes = new EnumMap<>(columns);
    }

    /**
     * @param columns 표의 칸 enum
     * @param <C>     칸 타입
     * @return 빈 패치
     */
    public static <C extends Enum<C>> Patch<C> of(Class<C> columns) {
        return new Patch<>(Objects.requireNonNull(columns, "columns"));
    }

    /**
     * 칸에 값을 적는다.
     *
     * @param column 칸
     * @param value  값. {@code null} 불가 — 모르면 부르지 않는다
     * @return 이 패치
     */
    public Patch<C> set(C column, Object value) {
        writes.put(Objects.requireNonNull(column, "column"),
                new Write(Objects.requireNonNull(value, () -> column + " 에 null 을 적을 수 없다 — 부재는 값이 아니다"),
                        false));
        return this;
    }

    /**
     * 칸이 비어 있을 때만 적는다 — <strong>키를 이루는 불변 속성</strong>에만 쓴다(§5.5 「DDL 정정」).
     * 여러 토픽이 같은 값의 사본을 싣는 칸이라 먼저 온 것이 쓰고 덮지 않는다. 개정으로 바뀔 수
     * 있는 값에 쓰면 옛 개정이 새 개정을 이기는 순서가 생긴다.
     *
     * @param column 칸
     * @param value  값
     * @return 이 패치
     */
    public Patch<C> setIfAbsent(C column, Object value) {
        writes.put(Objects.requireNonNull(column, "column"),
                new Write(Objects.requireNonNull(value, () -> column + " 에 null 을 적을 수 없다 — 부재는 값이 아니다"),
                        true));
        return this;
    }

    /** 적을 칸이 없는가. */
    public boolean isEmpty() {
        return writes.isEmpty();
    }

    /** 적을 칸들 (칸 선언 순서). */
    public Map<C, Write> writes() {
        return Collections.unmodifiableMap(writes);
    }

    /**
     * 칸 하나의 쓰기.
     *
     * @param value    값
     * @param ifAbsent 비어 있을 때만 쓰는가
     */
    public record Write(Object value, boolean ifAbsent) {
    }
}
