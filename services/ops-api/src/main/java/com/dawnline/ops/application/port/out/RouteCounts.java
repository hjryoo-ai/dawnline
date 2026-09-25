package com.dawnline.ops.application.port.out;

import com.dawnline.ops.domain.RouteProgress;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code rm_routes} 를 캠프 · 진행으로 센다 — {@code dawnline_routes} (DESIGN.md §9.1).
 *
 * <p><strong>집계만</strong> 낸다. 라우트 단위 진행은 ops-web 지도의 몫이다 — {@code routeId} 는 열린 라벨이라 시계열이 되면
 * 수가 끝없이 는다.
 */
public interface RouteCounts {

    /**
     * 끝나지 않은 라우트(진행 중 · 계획을 모름)는 창 없이 전부, 출발 전과 완료는 계획 출발이 {@code since} 이후인 것만
     * 센다(ADR-061 — 끝나지 않은 일에는 창이 없다). 없는 조합은 결과에 없다.
     *
     * @param since KPI 창의 첫 버킷 — 정시율과 같은 창. 출발 전 · 완료의 수를 묶는다
     * @return 캠프 · 진행별 수
     */
    List<CampRoutes> count(Instant since);

    /**
     * @param campId   캠프 — 모르면 {@code null}({@code delivery.status} 가 먼저 만든 행은 캠프를 모른다)
     * @param progress 진행
     * @param routes   라우트 수
     */
    record CampRoutes(@Nullable UUID campId, RouteProgress progress, long routes) {
        public CampRoutes {
            Objects.requireNonNull(progress, "progress");
        }
    }
}
