package com.dawnline.ops.domain;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 진행 축 판정 — 축 규칙의 다섯 번째 자리
 * (ADR-017 ·
 * ADR-051 결정 3).
 *
 * <h2>판정은 최댓값이다</h2>
 * 네 축은 전부 <strong>전순서</strong>이고(§5.5 「DDL 정정」 표), 이 클래스의 판정은 「더 앞선
 * 쪽이 남는다」 하나뿐이다. 그러면 두 사실이 어느 순서로 와도 칸에 남는 값이 같다 — 최댓값은
 * 교환법칙을 지키기 때문이다. 그 성질이 문장이 아니라 코드의 성질이 되게 하려고 같은 단계에 두
 * 값을 두지 않았다({@code PLANNED}·{@code UNSERVICEABLE} 처럼 함께 오지 않는 값도 서로 다른
 * 자리에 앉는다). {@code ProgressTest} 가 모든 쌍에 대해 그것을 확인한다.
 *
 * <p>「이 핸들러는 저 핸들러 뒤에 온다」는 문장이 여기 없다 — 판정은 현재 값과 들어온 값만 본다.
 *
 * <h2>개정·시각 비교도 같은 판정이다</h2>
 * {@code rm_routes.revision}(라우트 칸)·{@code planned_as_of}(주문의 계획 칸)·{@code eta_as_of}
 * 는 축이 아니라 값의 크기로 견주지만 규칙은 같다 — 큰 쪽이 남는다.
 */
public final class Progress {

    private Progress() {
    }

    /**
     * 진행 축 위의 두 값을 견준다.
     *
     * @param current  칸의 현재 값. 비어 있으면 {@code null}
     * @param incoming 이벤트가 말하는 값
     * @param <S>      축 — 선언 순서가 진행 순서인 enum
     * @return 적을지의 판정
     */
    public static <S extends Enum<S>> Verdict judge(@Nullable S current, S incoming) {
        Objects.requireNonNull(incoming, "incoming");
        if (current == null) {
            return Verdict.ADVANCE;
        }
        return compare(Integer.compare(incoming.ordinal(), current.ordinal()));
    }

    /**
     * 개정 번호·사건 시각처럼 크기로 견주는 값.
     *
     * <p>같은 값은 {@link Verdict#HOLD} 다 — 같은 번호의 재발행은 새 정보를 담지 않는다
     * ({@code route.assigned.v1} 의 {@code revision} 설명, tracking 의 {@code route_revisions} 와
     * 같은 술어 {@code <}).
     *
     * @param current  칸의 현재 값. 비어 있으면 {@code null}
     * @param incoming 이벤트가 말하는 값
     * @param <V>      비교 가능한 값
     * @return 적을지의 판정
     */
    public static <V extends Comparable<? super V>> Verdict judgeVersion(@Nullable V current, V incoming) {
        Objects.requireNonNull(incoming, "incoming");
        if (current == null) {
            return Verdict.ADVANCE;
        }
        return compare(incoming.compareTo(current));
    }

    private static Verdict compare(int incomingVersusCurrent) {
        if (incomingVersusCurrent > 0) {
            return Verdict.ADVANCE;
        }
        return incomingVersusCurrent == 0 ? Verdict.HOLD : Verdict.STALE;
    }
}
