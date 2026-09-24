package com.dawnline.ops.adapter.in.messaging;

import com.dawnline.messaging.EventEnvelope;
import com.dawnline.ops.application.port.in.Fact;
import com.dawnline.ops.domain.DeliveryOutcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * §4.1 의 열한 토픽 중 <strong>ops 가 읽는 필드만</strong> ({@code contracts/events/*.v1.schema.json}).
 *
 * <p>계약에 있는데 여기 없는 필드는 읽지 않는 것이다. {@code EventJson} 이 모르는 필드를 무시하므로
 * (§4.7) 발행자가 필드를 더해도 깨지지 않는다 — 소비자가 자기가 읽는 것만 선언하는 것이 소비자
 * 주도 계약의 요점이다. 특히 {@link RouteDeparted} 는 계약의 다섯 칸 중 셋만 읽는다 — {@code revision}·
 * {@code plannedDeparture} 는 출발 정시율(KPI 단계)의 자리다. 여섯째였던 {@code stopCount} 는
 * 이 소비자가 읽지 않아 계약에서 뺐다(ADR-050 재검토 지점 3).
 */
final class Payloads {

    private Payloads() {
    }

    /** 봉투를 받아 사실로 옮긴다 — 봉투의 값이 필요한 사실({@code route.assigned} 의 발행 시각)이 있다. */
    interface ToFact {
        Fact toFact(EventEnvelope<?> envelope);
    }

    /** 약속창 중 끝만. */
    record Window(Instant end) {
    }

    record OrderPlaced(UUID orderId, UUID customerId, String serviceTier, Window promisedWindow, Instant placedAt)
            implements ToFact {
        @Override
        public Fact toFact(EventEnvelope<?> envelope) {
            return new Fact.OrderPlaced(orderId, customerId, serviceTier, promisedWindow.end(), placedAt);
        }
    }

    record FulfillmentPlanned(String outcome, UUID orderId, String serviceTier, Window promisedWindow,
            @Nullable UUID campId, @Nullable UUID waveId, @Nullable Instant waveCutoffAt) implements ToFact {

        /** {@code outcome} 의 배차 성공 값 (계약 enum). */
        static final String PLANNED = "PLANNED";

        @Override
        public Fact toFact(EventEnvelope<?> envelope) {
            return new Fact.FulfillmentPlanned(orderId, PLANNED.equals(outcome), campId, waveId,
                    serviceTier, waveCutoffAt, promisedWindow.end());
        }
    }

    record OrderDispatched(UUID orderId) implements ToFact {
        @Override
        public Fact toFact(EventEnvelope<?> envelope) {
            return new Fact.OrderDispatched(orderId);
        }
    }

    record OrderCancelled(UUID orderId) implements ToFact {
        @Override
        public Fact toFact(EventEnvelope<?> envelope) {
            return new Fact.OrderCancelled(orderId);
        }
    }

    record WaveClosed(UUID waveId, UUID campId, String serviceTier, Instant cutoffAt) implements ToFact {
        @Override
        public Fact toFact(EventEnvelope<?> envelope) {
            return new Fact.WaveClosed(waveId, campId, serviceTier, cutoffAt);
        }
    }

    record RouteAssigned(UUID routeId, UUID planId, UUID campId, UUID vehicleId, UUID driverId,
            int revision, Summary summary, List<Stop> stops) implements ToFact {

        record Summary(int stopCount, int distanceM, int costKrw, Instant plannedDeparture) {
        }

        record Stop(List<UUID> orderIds, Instant plannedArrival) {
        }

        @Override
        public Fact toFact(EventEnvelope<?> envelope) {
            // 발행 시각은 봉투에 있다 — 주문의 계획 칸을 라우트를 넘어 견주는 값이다(§5.5 「DDL 정정」).
            return new Fact.RouteAssigned(routeId, planId, campId, vehicleId, driverId, revision,
                    summary.stopCount(), summary.distanceM(), summary.costKrw(), summary.plannedDeparture(),
                    envelope.occurredAt(),
                    stops.stream().map(s -> new Fact.AssignedStop(s.orderIds(), s.plannedArrival())).toList());
        }
    }

    record PlanCompleted(UUID planId, UUID waveId, UUID campId, int routeCount, int unassignedCount,
            long totalCostKrw, int planDurationMs) implements ToFact {
        @Override
        public Fact toFact(EventEnvelope<?> envelope) {
            return new Fact.PlanCompleted(waveId, campId, planId, routeCount, unassignedCount, totalCostKrw,
                    planDurationMs);
        }
    }

    record PlanFailed(UUID waveId, UUID campId) implements ToFact {
        @Override
        public Fact toFact(EventEnvelope<?> envelope) {
            return new Fact.PlanFailed(waveId, campId);
        }
    }

    record DeliveryStatus(UUID routeId, List<UUID> orderIds, String status, Instant occurredAt)
            implements ToFact {

        /** 결과가 아닌 값 (계약 enum). 라우트가 출발했다는 사실만 싣는다. */
        static final String ARRIVED = "ARRIVED";

        @Override
        public Fact toFact(EventEnvelope<?> envelope) {
            DeliveryOutcome outcome = ARRIVED.equals(status) ? null : DeliveryOutcome.valueOf(status);
            return new Fact.DeliveryStatus(routeId, orderIds, outcome, occurredAt);
        }
    }

    record DeliveryAtRisk(UUID routeId, UUID campId, Instant detectedAt, List<RemainingStop> remainingStops)
            implements ToFact {

        record RemainingStop(List<UUID> orderIds, Instant etaAt) {
        }

        @Override
        public Fact toFact(EventEnvelope<?> envelope) {
            return new Fact.DeliveryAtRisk(routeId, campId, detectedAt, remainingStops.stream()
                    .flatMap(stop -> stop.orderIds().stream().map(id -> new Fact.OrderEta(id, stop.etaAt())))
                    .toList());
        }
    }

    record RouteDeparted(UUID routeId, UUID campId, Instant departedAt) implements ToFact {
        @Override
        public Fact toFact(EventEnvelope<?> envelope) {
            return new Fact.RouteDeparted(routeId, campId, departedAt);
        }
    }
}
