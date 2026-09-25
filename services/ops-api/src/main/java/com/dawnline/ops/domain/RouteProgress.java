package com.dawnline.ops.domain;

import java.util.Locale;

/**
 * 라우트의 진행 — {@code dawnline_routes{status}} 의 값 (DESIGN.md §9.1).
 *
 * <p>{@link RouteStatus} 가 아니다. {@code rm_routes.status} 는 둘({@code ASSIGNED} · {@code DEPARTED})뿐이고, 「완료」는
 * 어떤 토픽도 말하지 않는다 — 출발한 라우트의 주문이 전부 결과가 났다는 <em>사실들</em>에서 판정한다. 판정은
 * {@code JdbcRouteCounts} 의 한 문장에 있고, 값의 이름은 그 문장의 리터럴이다.
 */
public enum RouteProgress {

    /** 출발 전 — {@code status = 'ASSIGNED'}. */
    ASSIGNED,

    /** 출발했고, 결과가 없는 주문(취소 제외)이 남았다. */
    IN_PROGRESS,

    /** 출발했고, 이 라우트의 주문(취소 제외)이 전부 결과가 났다. */
    COMPLETED,

    /**
     * 계획({@code route.assigned})이 아직 오지 않았다 — 진행을 판정할 기준(그 라우트의 주문)이 없다. 부재는 값이
     * 아니지만 부재의 수는 값이다(ADR-051).
     */
    UNKNOWN;

    /** @return 라벨 값 */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
