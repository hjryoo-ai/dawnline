package com.dawnline.dispatch.domain;

import java.util.Objects;

/**
 * {@code delivery.status} 하나가 {@code route_stops.status} 에 무엇을 하는가 — <strong>순수
 * 함수</strong>다 (ADR-047 결정 4).
 *
 * <h2>왜 애그리거트 메서드가 아닌가</h2>
 * 불변규칙 6 은 상태 전이를 애그리거트 메서드로만 하라고 적는다. {@code route_stops} 에는
 * 애그리거트가 없다 — 라우트는 120 stop 까지 가고 {@code RouteMutations} 는 「애그리거트를
 * 되살리지 않는다」를 명시한 포트다(§5.3 운영자 재배정). 규칙이 <em>한 곳에</em> 있다는 목적은
 * 이 클래스가 지키고, 어댑터는 판정만 받아 한 행을 쓴다. <strong>세터를 막는 장치는 없으므로</strong>
 * 이 예외는 §13 매핑표 불변규칙 6 행에 적혀 있다.
 *
 * <h2>축 규칙 그대로</h2>
 * 앞으로 가는 전이는 전부 허용한다(건너뜀은 정식이다 — §5.1). 뒤로 가거나 제자리인 것은
 * <em>철 지난 이벤트</em>이고, 취소된 stop 은 축 밖이라 먼저 묻는다.
 */
public final class RouteStopTransition {

    private RouteStopTransition() {
    }

    /**
     * 판정.
     */
    public enum Verdict {

        /** 전이한다. 새 상태는 보고된 값 그대로다. */
        APPLY,

        /** 철 지난 이벤트 — 무시하고 센다 ({@code dawnline_event_stale_total}). */
        STALE,

        /**
         * 취소된 stop 에 도착했다 — 무시하고 센다 ({@code dawnline_scan_after_cancel_total}).
         *
         * <p>dispatch 에서 {@code CANCELLED} 는 「계획에서 뺐다」는 뜻이다. §6.10 이 이미 시각을
         * 재전파하고 새 개정을 발행한 자리이므로, 뒤늦게 {@code COMPLETED} 를 적으면 이미 나간
         * 개정과 저장된 계획이 어긋난다.
         */
        AFTER_CANCEL
    }

    /**
     * 이 보고가 이 stop 에 무엇을 하는가.
     *
     * @param current  저장된 상태
     * @param reported {@code delivery.status} 가 말하는 상태 — {@code ARRIVED}·{@code COMPLETED}·
     *                 {@code FAILED} 중 하나여야 한다. 계약이 그 셋만 허용하고, 모르는 값은
     *                 리스너가 여기 오기 전에 거른다(§4.7)
     * @return 판정
     * @throws IllegalArgumentException {@code reported} 가 진행 축 밖이거나 {@code PLANNED} 일 때 —
     *                                  발행자가 계약을 어긴 것이고 조용히 무시하면 그 사실이 사라진다
     */
    public static Verdict decide(RouteStopStatus current, RouteStopStatus reported) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(reported, "reported");
        if (!reported.visited()) {
            throw new IllegalArgumentException(
                    "delivery.status 가 나를 수 없는 상태입니다: " + reported);
        }
        if (current == RouteStopStatus.CANCELLED) {
            return Verdict.AFTER_CANCEL;
        }
        return reported.stage() > current.stage() ? Verdict.APPLY : Verdict.STALE;
    }
}
