package com.dawnline.ops.application.port.out;

import com.dawnline.ops.domain.RouteStatus;
import com.dawnline.ops.domain.WaveStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 읽기 모델 조회 — 캠프 대시보드와 라우트 지도가 읽는 것 (DESIGN.md §5.5 「조회」).
 *
 * <p>칸이 {@code null} 이면 그 사실이 아직 오지 않았다는 뜻이다 — 부재는 값이 아니다(ADR-051). 조회가 그 자리를
 * {@code 0}·{@code false} 로 채우지 않는다: 화면이 「모름」과 「없음」을 구별해 그려야 한다.
 */
public interface ReadModelViews {

    /** @return 웨이브가 하나라도 있는 캠프들, 캠프 id 순 */
    List<CampSummary> camps();

    /**
     * @param campId 캠프
     * @param from   컷오프 창의 시작(포함)
     * @param to     컷오프 창의 끝(제외)
     * @param limit  최대 행 수
     * @return 컷오프 순
     */
    List<WaveSummary> waves(UUID campId, Instant from, Instant to, int limit);

    /**
     * @param waveId 웨이브
     * @return 웨이브의 계획과 창고, 없는 웨이브면 비어 있다
     */
    Optional<WavePlan> wavePlan(UUID waveId);

    /**
     * @param planId 계획
     * @return 그 계획의 라우트들, 라우트 id 순
     */
    List<RouteSummary> routesOf(UUID planId);

    /**
     * 취소됐는데 배송된 주문 — §6.10 넷째 분기({@code dawnline_cancel_too_late_total})가 세는 건의 목록이다. 창은
     * KPI 와 같은 버킷 창이고, 버킷 식은 {@code ix_rmo_delivery_hour} 의 식 그대로다.
     *
     * @param campId  캠프
     * @param buckets 배송 축의 버킷 창
     * @param limit   최대 행 수
     * @return 배송 시각 역순
     */
    List<CancelledButDelivered> cancelledButDelivered(UUID campId, DeliveryKpis.Buckets buckets, int limit);

    /**
     * @param campId            캠프
     * @param waves             웨이브 수
     * @param latestCutoffAt    가장 늦은 컷오프
     */
    record CampSummary(UUID campId, long waves, @Nullable Instant latestCutoffAt) {
        public CampSummary {
            Objects.requireNonNull(campId, "campId");
        }
    }

    /**
     * {@code rm_waves} 한 행.
     *
     * @param waveId          웨이브
     * @param serviceTier     등급
     * @param cutoffAt        컷오프
     * @param status          상태 — 모르면 {@code null}
     * @param orderCount      편입된 것으로 알려진 주문 수(집계)
     * @param planId          계획 ({@code plan.completed})
     * @param planDurationMs  계획 시간
     * @param totalCostKrw    총비용 (불변규칙 9)
     * @param unassignedCount 미배정
     * @param routeCount      라우트 수 — 기대치(ADR-024)
     */
    record WaveSummary(UUID waveId, @Nullable String serviceTier, @Nullable Instant cutoffAt,
            @Nullable WaveStatus status, @Nullable Integer orderCount, @Nullable UUID planId,
            @Nullable Integer planDurationMs, @Nullable Long totalCostKrw, @Nullable Integer unassignedCount,
            @Nullable Integer routeCount) {
        public WaveSummary {
            Objects.requireNonNull(waveId, "waveId");
        }
    }

    /**
     * 웨이브의 계획과 창고.
     *
     * @param waveId 웨이브
     * @param planId 계획 — 아직 없으면 {@code null}
     * @param depot  창고 — {@code wave.closed} 가 아직 오지 않았으면 {@code null}
     */
    record WavePlan(UUID waveId, @Nullable UUID planId, @Nullable Depot depot) {
        public WavePlan {
            Objects.requireNonNull(waveId, "waveId");
        }
    }

    /**
     * 창고 좌표 — 라우트의 출발·복귀 지점.
     *
     * @param lat 위도
     * @param lng 경도
     */
    record Depot(double lat, double lng) {
    }

    /**
     * {@code rm_routes} 한 행.
     *
     * @param routeId          라우트
     * @param vehicleId        차량
     * @param revision         revision
     * @param status           상태
     * @param plannedDeparture 계획 출발
     * @param departedAt       실제 출발 — 출발 전이면 {@code null}
     * @param stopCount        stop 수
     * @param completedCount   완료 주문 수(집계)
     * @param failedCount      실패 주문 수(집계)
     * @param atRisk           at-risk 가 온 적이 있다 — 오기 전에는 {@code null}({@code false} 는 주장이다)
     * @param distanceM        거리
     * @param costKrw          비용
     */
    record RouteSummary(UUID routeId, @Nullable UUID vehicleId, @Nullable Integer revision,
            @Nullable RouteStatus status, @Nullable Instant plannedDeparture, @Nullable Instant departedAt,
            @Nullable Integer stopCount, @Nullable Integer completedCount, @Nullable Integer failedCount,
            @Nullable Boolean atRisk, @Nullable Integer distanceM, @Nullable Integer costKrw) {
        public RouteSummary {
            Objects.requireNonNull(routeId, "routeId");
        }
    }

    /**
     * 취소됐는데 배송된 주문 하나 — 주소는 싣지 않는다(읽기 모델에 없다, §10).
     *
     * @param orderId     주문
     * @param waveId      웨이브
     * @param routeId     라우트
     * @param deliveredAt 배송 완료 시각
     */
    record CancelledButDelivered(UUID orderId, @Nullable UUID waveId, @Nullable UUID routeId, Instant deliveredAt) {
        public CancelledButDelivered {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(deliveredAt, "deliveredAt");
        }
    }
}
