package com.dawnline.ops.application.port.in;

import com.dawnline.ops.domain.DeliveryOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 읽기 모델이 받는 사실 — §4.1 의 토픽 열한 개 중 <strong>ops 가 읽는 칸만</strong> (DESIGN.md §5.5).
 *
 * <p>토픽 하나에 변형 하나다. {@code sealed} 로 닫아 두어 프로젝터의 {@code switch} 가 전부를
 * 다루는지 컴파일러가 본다 — 토픽이 붙으면 변형이 붙고, 그 변형을 다루지 않는 프로젝터는
 * 컴파일되지 않는다. 그 사슬의 다른 끝(리스너가 모든 토픽을 구독하는가)은 {@code ProjectionTopicsTest}
 * 가 본다.
 *
 * <p>여기 없는 계약 필드는 읽지 않는 것이다. 특히 {@link RouteDeparted} 는 계약의 다섯 칸 중
 * 셋만 읽는다(ADR-050 재검토 지점 3 에서 여섯째 {@code stopCount} 를 뺐다).
 */
public sealed interface Fact {

    /**
     * {@code order.placed}.
     *
     * @param orderId     주문
     * @param customerId  고객
     * @param serviceTier 티어
     * @param promisedEnd 고객이 <em>처음</em> 받은 약속의 끝 — 원 약속 기준 정시율의 기준선(§8.1)
     * @param placedAt    접수 시각 — 접수 축 KPI 의 버킷(§5.5 「KPI — 두 축, 뷰」)
     */
    record OrderPlaced(UUID orderId, UUID customerId, String serviceTier, Instant promisedEnd, Instant placedAt)
            implements Fact {
        public OrderPlaced {
            Objects.requireNonNull(orderId, "orderId");
        }
    }

    /**
     * {@code fulfillment.planned}. 결과가 둘이다.
     *
     * @param orderId      주문
     * @param serviceable  {@code outcome=PLANNED} 이면 참. 거짓이면 아래 넷은 비어 있다
     * @param campId       캠프
     * @param waveId       웨이브
     * @param serviceTier  티어 — 웨이브 키의 사본
     * @param waveCutoffAt 웨이브 컷오프 — 웨이브 키의 사본
     * @param promisedEnd  (개정됐을 수 있는) 약속의 끝. 개정이 없으면 원 약속과 같다
     */
    record FulfillmentPlanned(UUID orderId, boolean serviceable, @Nullable UUID campId,
            @Nullable UUID waveId, String serviceTier, @Nullable Instant waveCutoffAt,
            Instant promisedEnd) implements Fact {
        public FulfillmentPlanned {
            Objects.requireNonNull(orderId, "orderId");
        }
    }

    /**
     * {@code order.dispatched}. 계약의 {@code routeId} 를 읽지 않는다 — 주문의 라우트는
     * {@code route.assigned} 가 쓴다. 재계획은 이 이벤트를 다시 내지 않으므로(§6.8) 둘이 같은 칸을
     * 쓰면 옮긴 뒤에 갈라진다(§5.5 「DDL 정정」).
     *
     * @param orderId 주문
     */
    record OrderDispatched(UUID orderId) implements Fact {
    }

    /**
     * {@code order.cancelled}.
     *
     * @param orderId 주문
     */
    record OrderCancelled(UUID orderId) implements Fact {
    }

    /**
     * {@code wave.closed}. 계약의 {@code orderCount} 를 읽지 않는다 — {@code rm_waves.order_count}
     * 는 {@code rm_orders} 의 집계다(ADR-051 결정 4).
     *
     * @param waveId      웨이브
     * @param campId      웨이브 키
     * @param serviceTier 웨이브 키
     * @param cutoffAt    웨이브 키
     * @param depotLat    창고 위도 — 계약 필수, 지도의 원점
     * @param depotLng    창고 경도
     * @param campCode    캠프 코드 — 계약에서 선택(2026-09-24 추가). 그 전의 이벤트에는 없다
     */
    record WaveClosed(UUID waveId, UUID campId, String serviceTier, Instant cutoffAt, double depotLat,
            double depotLng, @Nullable String campCode) implements Fact {
    }

    /**
     * {@code route.assigned} — 최초 확정과 재계획의 개정 모두.
     *
     * @param routeId          라우트
     * @param planId           계획
     * @param campId           라우트의 키 속성
     * @param vehicleId        차량
     * @param driverId         기사
     * @param revision         라우트 칸을 거르는 번호 (§6.8 4단계, ADR-045)
     * @param stopCount        stop 수
     * @param distanceM        거리
     * @param costKrw          비용 (정수 KRW, 불변규칙 9)
     * @param plannedDeparture 계획 출발
     * @param asOf             봉투의 {@code occurredAt} — 주문의 계획 칸을 라우트를 넘어 견주는 값
     * @param stops            stop 들. 취소된 주문도 남아 있다(ADR-026)
     */
    record RouteAssigned(UUID routeId, UUID planId, UUID campId, UUID vehicleId, UUID driverId,
            int revision, int stopCount, int distanceM, int costKrw, Instant plannedDeparture,
            Instant asOf, List<AssignedStop> stops) implements Fact {
        public RouteAssigned {
            stops = List.copyOf(stops);
        }
    }

    /**
     * {@code route.assigned} 의 stop 하나.
     *
     * @param orderIds       이 stop 의 주문들
     * @param plannedArrival 계획 도착
     */
    record AssignedStop(List<UUID> orderIds, Instant plannedArrival) {
        public AssignedStop {
            orderIds = List.copyOf(orderIds);
        }
    }

    /**
     * {@code plan.completed}.
     *
     * @param waveId          웨이브
     * @param campId          웨이브 키
     * @param planId          계획
     * @param routeCount      기다려야 하는 {@code route.assigned} 수 — 기대치(ADR-024)
     * @param unassignedCount 미배정
     * @param totalCostKrw    총비용
     * @param planDurationMs  계획 시간
     */
    record PlanCompleted(UUID waveId, UUID campId, UUID planId, int routeCount, int unassignedCount,
            long totalCostKrw, int planDurationMs) implements Fact {
    }

    /**
     * {@code plan.failed}. 계획 id 는 읽지 않는다 — 실패한 계획은 {@code rm_waves.plan_id} 의
     * 출처가 아니다(재시도가 성공하면 그 계획이 그 칸의 사실이다).
     *
     * @param waveId 웨이브
     * @param campId 웨이브 키
     */
    record PlanFailed(UUID waveId, UUID campId) implements Fact {
    }

    /**
     * {@code delivery.status}.
     *
     * @param routeId    스캔이 일어난 라우트 — 라우트가 출발했다는 사실의 출처다. 주문의 라우트를
     *                   정하지 않는다(사실은 주문으로 식별한다, ADR-047)
     * @param orderIds   주문들
     * @param outcome    결과. {@code ARRIVED} 면 비어 있다 — 결과가 아니다
     * @param occurredAt 사건 시각
     */
    record DeliveryStatus(UUID routeId, List<UUID> orderIds, @Nullable DeliveryOutcome outcome,
            Instant occurredAt) implements Fact {
        public DeliveryStatus {
            orderIds = List.copyOf(orderIds);
        }
    }

    /**
     * {@code delivery.at-risk}.
     *
     * @param routeId    라우트
     * @param campId     라우트의 키 속성
     * @param detectedAt 판정 시각 — {@code eta_as_of} 로 견준다
     * @param etas       남은 주문들의 ETA
     */
    record DeliveryAtRisk(UUID routeId, UUID campId, Instant detectedAt, List<OrderEta> etas) implements Fact {
        public DeliveryAtRisk {
            etas = List.copyOf(etas);
        }
    }

    /**
     * 주문 하나의 ETA.
     *
     * @param orderId 주문
     * @param etaAt   ETA
     */
    record OrderEta(UUID orderId, Instant etaAt) {
    }

    /**
     * {@code delivery.route-departed} (ADR-050).
     *
     * @param routeId    라우트
     * @param campId     라우트의 키 속성
     * @param departedAt 출발 시각
     */
    record RouteDeparted(UUID routeId, UUID campId, Instant departedAt) implements Fact {
    }
}
