package com.dawnline.ops.domain;

import java.util.Locale;

/**
 * 라우트의 진행 — {@code dawnline_routes{status}} 의 값 (DESIGN.md §9.1).
 *
 * <p>{@link RouteStatus} 가 아니다. {@code rm_routes.status} 는 둘({@code ASSIGNED} · {@code DEPARTED})뿐이고, 「완료」는
 * 어떤 토픽도 말하지 않는다 — 출발한 라우트의 주문이 전부 결과가 났다는 <em>사실들</em>에서 판정한다. 판정은 쓰기 때
 * 재집계가 {@code rm_routes.completed_at} · {@code live_count} 로 적고(ADR-061), {@code JdbcRouteCounts} 의 한 문장이 그
 * 칸들을 읽는다. 값의 이름은 그 문장의 리터럴이다.
 */
public enum RouteProgress {

    /** 출발 전 — {@code status = 'ASSIGNED'}, 비취소 주문이 있다. 창 없이 센다. */
    ASSIGNED,

    /** 출발했고, 결과가 없는 주문(취소 제외)이 남았다 — 창 없이 센다. */
    IN_PROGRESS,

    /** 출발했고, 이 라우트의 주문(취소 제외)이 전부 결과가 났다. */
    COMPLETED,

    /**
     * 할 일이 없다 — 비취소 주문이 없다({@code live_count = 0}). 재계획이 주문을 전부 옮겼거나 전부 취소됐다. 완료가
     * 아니다: 한 번도 돌지 않은 라우트를 {@link #COMPLETED} 에 섞지 않고, 일어나지 않은 완료에 시각을 만들지 않는다.
     * 진행 중도 아니다: 기다릴 것이 없다. 출발 여부와 무관하다.
     */
    VOID,

    /**
     * 계획({@code route.assigned})이 아직 오지 않았다 — 진행을 판정할 기준(그 라우트의 주문)이 없다. 부재는 값이
     * 아니지만 부재의 수는 값이다(ADR-051). 창 없이 센다.
     */
    UNKNOWN;

    /** @return 라벨 값 */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
