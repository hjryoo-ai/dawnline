package com.dawnline.dispatch.application;

import com.dawnline.dispatch.application.port.in.RecordRouteDepartureUseCase;
import com.dawnline.dispatch.application.port.out.RouteMutations;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출발을 자기 DB 에 둔다 — {@code routes.departed_at} (DESIGN.md §6.8 「편차」, ADR-072).
 *
 * <p>at-risk 는 설계상 출발 지연에서 첫 stop 전에 발화한다(§5.4). 그 순간 dispatch 에 닿은 stop 이 없으면 재계획은 편차를 모른다 — 7-4 의
 * {@code no-anchor} 29/32 가 그 자리였다. 출발 시각을 여기 두면 앵커가 {@code departed_at − planned_departure} 가 된다: 소속도 시각도 자기
 * DB 에서(ADR-048). 처음 온 값만 남는다 — 어댑터의 {@code COALESCE} 가 지킨다.
 */
public class RecordRouteDepartureService implements RecordRouteDepartureUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordRouteDepartureService.class);

    private final RouteMutations routes;

    /**
     * @param routes 라우트 조작
     */
    public RecordRouteDepartureService(RouteMutations routes) {
        this.routes = Objects.requireNonNull(routes, "routes");
    }

    @Override
    @Transactional
    public boolean record(RouteDeparted command) {
        Objects.requireNonNull(command, "command");
        if (!routes.markDeparted(command.routeId(), command.departedAt())) {
            // 보존이 라우트를 지운 뒤의 재생이다 — 사실은 참이지만 적을 자리가 없다(ADR-047 기각 (3) 과 같은 판단).
            log.debug("없는 라우트의 출발이다. routeId={}", command.routeId());
            return false;
        }
        log.debug("라우트가 떠났다. routeId={}, departedAt={}", command.routeId(), command.departedAt());
        return true;
    }
}
