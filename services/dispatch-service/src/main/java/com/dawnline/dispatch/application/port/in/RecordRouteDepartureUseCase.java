package com.dawnline.dispatch.application.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 라우트가 캠프를 떠났다 — {@code delivery.route-departed} (ADR-072).
 *
 * <p>출발은 사실이다 — 개정 번호로 거르지 않는다(ADR-047 결정 3). 재계획이 닿은 stop 이 없을 때 이 값을 앵커로 쓴다.
 */
public interface RecordRouteDepartureUseCase {

    /**
     * @param command 출발 한 건
     * @return 적었으면 참. 라우트가 dispatch 에 없으면(보존이 지운 뒤의 재생) 거짓 — 부르는 쪽이 커밋 뒤에 철 지난 사건으로 센다
     */
    boolean record(RouteDeparted command);

    /**
     * 출발 한 건.
     *
     * @param routeId    라우트 id
     * @param departedAt 떠난 시각 — 기사 단말의 사건 시각
     */
    record RouteDeparted(UUID routeId, Instant departedAt) {

        public RouteDeparted {
            Objects.requireNonNull(routeId, "routeId");
            Objects.requireNonNull(departedAt, "departedAt");
        }
    }
}
