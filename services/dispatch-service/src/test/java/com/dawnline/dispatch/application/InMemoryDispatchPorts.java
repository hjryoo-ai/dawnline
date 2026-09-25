package com.dawnline.dispatch.application;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.DispatchEvents;
import com.dawnline.dispatch.application.port.out.PlannedRouteRepository;
import com.dawnline.dispatch.application.port.out.RouteMutations;
import com.dawnline.dispatch.application.port.out.RoutePlanRepository;
import com.dawnline.dispatch.application.port.out.RouteSnapshot;
import com.dawnline.dispatch.application.port.out.RuleCatalog;
import com.dawnline.dispatch.application.port.out.VehicleCatalog;
import com.dawnline.dispatch.domain.CandidateStatus;
import com.dawnline.dispatch.domain.RouteStopStatus;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.PlanStatus;
import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.dispatch.domain.optimizer.Capacity;
import com.dawnline.dispatch.domain.optimizer.Explanation;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleAttrs;
import com.dawnline.dispatch.domain.optimizer.VehicleCost;
import com.dawnline.dispatch.domain.optimizer.VehicleId;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 유스케이스 단위 테스트의 메모리 포트들.
 *
 * <p>목 프레임워크를 쓰지 않는다 — 여기서 보려는 것은 "무엇을 불렀나" 가 아니라 "무엇이 남았나"
 * 이고, 그건 진짜 저장소 흉내가 더 정직하다.
 */
final class InMemoryDispatchPorts {

    private InMemoryDispatchPorts() {
    }

    /** 캠프 기준 좌표. */
    static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);

    static VehicleCatalog fleet(int count, Instant startedAt) {
        List<VehicleSpec> vehicles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            vehicles.add(new VehicleSpec(VehicleId.of(Ids.newId()),
                    new Capacity(1_000_000, 5_000_000),
                    new VehicleAttrs("VAN", true, true),
                    new TimeWindow(startedAt, startedAt.plus(Duration.ofHours(10))),
                    VehicleCost.krw(45_000, 600, 250)));
        }
        return (campId, planFor) -> vehicles;
    }

    static RuleCatalog rules(RuleSet ruleSet) {
        return campId -> ruleSet;
    }

    /** {@code route_plans} 흉내. {@code wave_id} UNIQUE 를 그대로 지킨다. */
    static final class Plans implements RoutePlanRepository {

        private final Map<UUID, RoutePlan> byId = new LinkedHashMap<>();
        private final Map<UUID, UUID> byWave = new LinkedHashMap<>();

        @Override
        public boolean insertIfAbsent(RoutePlan plan) {
            if (byWave.containsKey(plan.waveId())) {
                return false;
            }
            byWave.put(plan.waveId(), plan.id());
            byId.put(plan.id(), plan);
            return true;
        }

        @Override
        public Optional<RoutePlan> findByWaveId(UUID waveId) {
            return Optional.ofNullable(byWave.get(waveId)).map(byId::get);
        }

        @Override
        public Optional<RoutePlan> findById(UUID planId) {
            return Optional.ofNullable(byId.get(planId));
        }

        @Override
        public Optional<Duration> lastPublishedDuration(UUID campId) {
            // 마지막으로 발행된 것 — 삽입 순서가 곧 발행 순서다(이 흉내에서는 한 번에 하나씩 돈다).
            return byId.values().stream()
                    .filter(plan -> plan.campId().equals(campId))
                    .filter(plan -> plan.status() == PlanStatus.PUBLISHED)
                    .flatMap(plan -> plan.planDurationMs().stream())
                    .reduce((first, second) -> second)
                    .map(Duration::ofMillis);
        }

        @Override
        public void update(RoutePlan plan) {
            byId.put(plan.id(), plan);
        }

        /** 저장된 계획 수. */
        int size() {
            return byId.size();
        }
    }

    /** {@code dispatch_candidates} 흉내. */
    static final class Candidates implements DispatchCandidateRepository {

        private final Map<UUID, DispatchCandidate> rows = new LinkedHashMap<>();

        @Override
        public boolean insertIfAbsent(DispatchCandidate candidate) {
            return rows.putIfAbsent(candidate.orderId(), candidate) == null;
        }

        @Override
        public Optional<DispatchCandidate> findById(UUID orderId) {
            return Optional.ofNullable(rows.get(orderId));
        }

        @Override
        public List<DispatchCandidate> findPlannableInWave(UUID waveId) {
            return rows.values().stream()
                    .filter(candidate -> candidate.waveId().equals(waveId))
                    .filter(candidate -> candidate.status().isPlannable())
                    .toList();
        }

        @Override
        public void update(DispatchCandidate candidate) {
            rows.put(candidate.orderId(), candidate);
        }

        /**
         * <strong>애그리거트의 전이 규칙을 그대로 쓴다.</strong> 어댑터는 같은 규칙을
         * {@code WHERE status = 'PENDING'} 으로 옮겨 적으므로(ADR-029), 이 대역이 SQL 을
         * 흉내 내면 둘이 어긋나도 아무 테스트가 실패하지 않는다. 기준은 도메인이다.
         */
        @Override
        public int recordPlanResult(java.util.Collection<UUID> orderIds,
                com.dawnline.dispatch.domain.CandidateStatus target, java.time.Instant at) {
            int changed = 0;
            for (UUID orderId : orderIds) {
                DispatchCandidate candidate = rows.get(orderId);
                if (candidate != null && candidate.recordPlanResult(target, at)) {
                    rows.put(orderId, candidate);
                    changed++;
                }
            }
            return changed;
        }

        /** 직접 넣는다. */
        void put(DispatchCandidate candidate) {
            rows.put(candidate.orderId(), candidate);
        }
    }

    /** 라우트 저장 흉내. id 를 부여하고 기록만 남긴다. */
    static final class Routes implements PlannedRouteRepository {

        final List<UUID> saved = new ArrayList<>();
        final List<Explanation> explanations = new ArrayList<>();

        @Override
        public List<UUID> saveRoutes(UUID planId, List<PlannedRoute> routes) {
            List<UUID> ids = routes.stream().map(route -> Ids.newId()).toList();
            saved.addAll(ids);
            return ids;
        }

        @Override
        public void saveExplanations(UUID planId, List<Explanation> explanations,
                Map<VehicleId, UUID> routeIds) {
            this.explanations.addAll(explanations);
        }
    }

    /**
     * 취소가 보이는 라우트 조작 흉내 (§6.10).
     *
     * <p>어느 주문이 죽었는지를 자기가 들지 않고 {@link Candidates} 를 본다 — 실제 SQL 도
     * {@code dispatch_candidates.status} 를 조인해서 판단하고, 진실이 두 곳에 있으면 페이크가
     * 실물과 다르게 굴어도 테스트는 통과한다.
     */
    static final class CancellableRoutes implements RouteMutations {

        private final Candidates candidates;
        private final Map<UUID, RouteHeader> headers = new LinkedHashMap<>();
        private final Map<UUID, List<StopRow>> rows = new LinkedHashMap<>();
        private final Map<UUID, Integer> revisions = new LinkedHashMap<>();
        private final Map<UUID, PlannedRoute> summaries = new LinkedHashMap<>();
        /** 계획 출발 시각. 개정 발행이 required 로 싣는다 (§5.3 V7, Phase 5-1b). */
        private final Map<UUID, Instant> departures = new LinkedHashMap<>();

        /** {@code routes.last_replanned_at} (V10). 재계획 쿨다운이 보는 값이다. */
        private final Map<UUID, Instant> lastReplannedAt = new LinkedHashMap<>();

        /** 시각을 다시 쓴 결과. {@code null} 이면 살아 있는 stop 이 하나도 없었다는 뜻이다. */
        final Map<UUID, PlannedRoute> retimed = new LinkedHashMap<>();

        CancellableRoutes(Candidates candidates) {
            this.candidates = candidates;
        }

        /** {@code route_stops} 한 행. */
        static final class StopRow {

            final UUID id = Ids.newId();
            /** {@code rewrite} 가 다시 매긴다 — 실물과 같다. */
            int seq;
            final GeoPoint point;
            final int serviceSeconds;
            /** {@code moveOrder} 가 줄이고 늘린다. */
            final List<UUID> orderIds;
            /** 이 stop 의 약속창. 개정 발행이 required 로 싣는다 (§5.3, Phase 5-1a). */
            final TimeWindow promised;
            Instant arrival;
            RouteStopStatus status = RouteStopStatus.PLANNED;
            /** 그 stop 에 처음 닿은 시각 (V10, ADR-048 결정 1). 닿지 않았으면 null 이다. */
            Instant actualAt;

            StopRow(int seq, GeoPoint point, int serviceSeconds, Instant arrival,
                    List<UUID> orderIds, TimeWindow promised) {
                this.seq = seq;
                this.point = point;
                this.serviceSeconds = serviceSeconds;
                this.arrival = arrival;
                this.orderIds = new ArrayList<>(orderIds);
                this.promised = Objects.requireNonNull(promised, "promised");
            }

            boolean cancelled() {
                return status == RouteStopStatus.CANCELLED;
            }
        }

        UUID route(UUID planId, UUID vehicleId, List<StopRow> stops) {
            UUID routeId = Ids.newId();
            headers.put(routeId, new RouteHeader(routeId, planId, vehicleId));
            rows.put(routeId, new ArrayList<>(stops));
            revisions.put(routeId, 1);
            // 첫 stop 도착보다 앞이면 된다 — 값 자체가 아니라 「있다」가 계약이다.
            departures.put(routeId, stops.getFirst().arrival.minusSeconds(600));
            return routeId;
        }

        StopRow row(UUID routeId, int seq) {
            return rows.get(routeId).stream().filter(stop -> stop.seq == seq).findFirst()
                    .orElseThrow();
        }

        @Override
        public Optional<RouteHeader> findHeader(UUID routeId) {
            return Optional.ofNullable(headers.get(routeId));
        }

        @Override
        public List<PositionedStop> loadPositionedStops(UUID routeId) {
            List<PositionedStop> live = new ArrayList<>();
            for (StopRow row : rows.getOrDefault(routeId, List.of())) {
                if (row.cancelled()) {
                    continue;
                }
                List<DispatchCandidate> alive = row.orderIds.stream()
                        .map(candidates::findById)
                        .flatMap(Optional::stream)
                        .filter(candidate -> candidate.status() != CandidateStatus.CANCELLED)
                        .toList();
                if (alive.isEmpty()) {
                    continue;
                }
                Parcel parcel = alive.stream()
                        .map(candidate -> new Parcel(candidate.weightG(), candidate.volumeCm3(),
                                candidate.requiresCold(), candidate.hazmat()))
                        .reduce(Parcel.EMPTY, Parcel::plus);
                live.add(new PositionedStop(row.seq, new Stop(row.point,
                        alive.stream().map(candidate -> OrderId.of(candidate.orderId())).toList(),
                        parcel, alive.getFirst().promised(), row.serviceSeconds,
                        alive.stream().mapToInt(DispatchCandidate::priority).max().orElse(0))));
            }
            return live;
        }

        @Override
        public List<RouteHeader> routesOfPlan(UUID planId) {
            return headers.values().stream()
                    .filter(header -> header.planId().equals(planId))
                    .toList();
        }

        @Override
        public Optional<AssignedStop> findAssignedStop(UUID orderId) {
            return rows.entrySet().stream()
                    .flatMap(entry -> entry.getValue().stream()
                            .filter(row -> row.orderIds.contains(orderId))
                            .map(row -> new AssignedStop(entry.getKey(), row.id, row.seq,
                                    row.status)))
                    .findFirst();
        }

        @Override
        public void markStopStatus(UUID stopId, RouteStopStatus status, Instant actualAt) {
            StopRow row = rows.values().stream().flatMap(List::stream)
                    .filter(stop -> stop.id.equals(stopId)).findFirst().orElseThrow();
            row.status = status;
            // 실물의 COALESCE 와 같다 — 처음 닿은 시각만 남는다 (ADR-048 결정 1).
            if (row.actualAt == null) {
                row.actualAt = actualAt;
            }
        }

        @Override
        public Optional<SettledStop> lastSettledStop(UUID routeId) {
            return rows.getOrDefault(routeId, List.of()).stream()
                    .filter(row -> row.actualAt != null)
                    .max(java.util.Comparator.comparingInt(row -> row.seq))
                    .map(row -> new SettledStop(row.seq, row.arrival, row.actualAt));
        }

        @Override
        public boolean tryStartReplan(UUID routeId, Instant now, java.time.Duration cooldown) {
            Instant last = lastReplannedAt.get(routeId);
            if (last != null && last.isAfter(now.minus(cooldown))) {
                return false;
            }
            lastReplannedAt.put(routeId, now);
            return true;
        }

        @Override
        public boolean cancelStopIfAllOrdersCancelled(UUID stopId) {
            StopRow row = rows.values().stream().flatMap(List::stream)
                    .filter(stop -> stop.id.equals(stopId)).findFirst().orElseThrow();
            if (row.status != RouteStopStatus.PLANNED) {
                return false;
            }
            boolean allDead = row.orderIds.stream().map(candidates::findById)
                    .flatMap(Optional::stream)
                    .allMatch(candidate -> candidate.status() == CandidateStatus.CANCELLED);
            if (allDead) {
                row.status = RouteStopStatus.CANCELLED;
            }
            return allDead;
        }

        @Override
        public void retime(UUID routeId, PlannedRoute route) {
            retimed.put(routeId, route);
            summaries.put(routeId, route);
            if (route == null) {
                return;
            }
            // 순번은 건드리지 않고 시각만 다시 쓴다 — 실물 SQL 과 같다.
            route.stops().forEach(planned -> rows.get(routeId).stream()
                    .filter(row -> row.point.equals(planned.stop().point()))
                    .forEach(row -> row.arrival = planned.arrival()));
        }

        @Override
        public Optional<RouteSnapshot> snapshot(UUID routeId) {
            RouteHeader header = headers.get(routeId);
            if (header == null) {
                return Optional.empty();
            }
            PlannedRoute summary = summaries.get(routeId);
            List<RouteSnapshot.StopSnapshot> stops = rows.get(routeId).stream()
                    .map(row -> new RouteSnapshot.StopSnapshot(row.seq, row.orderIds,
                            row.orderIds.stream()
                                    .filter(id -> candidates.findById(id)
                                            .map(candidate -> candidate.status()
                                                    == CandidateStatus.CANCELLED)
                                            .orElse(false))
                                    .toList(),
                            row.point.lat(), row.point.lng(), row.arrival, row.serviceSeconds,
                            row.cancelled(), row.promised))
                    .toList();
            return Optional.of(new RouteSnapshot(routeId, header.vehicleId(),
                    summary == null ? 0 : summary.distanceM(),
                    summary == null ? 0 : summary.durationS(),
                    summary == null ? 0L : summary.cost().krw(), departures.get(routeId), stops));
        }

        @Override
        public int bumpRevision(UUID routeId) {
            return revisions.merge(routeId, 1, Integer::sum);
        }

        @Override
        public Optional<UUID> findStopOf(UUID routeId, UUID orderId) {
            return rows.getOrDefault(routeId, List.of()).stream()
                    .filter(row -> row.orderIds.contains(orderId))
                    .map(row -> row.id)
                    .findFirst();
        }

        @Override
        public void moveOrder(UUID fromStopId, UUID orderId, UUID targetRouteId) {
            StopRow from = rows.values().stream().flatMap(List::stream)
                    .filter(row -> row.id.equals(fromStopId)).findFirst().orElseThrow();
            DispatchCandidate candidate = candidates.findById(orderId).orElseThrow();
            from.orderIds.remove(orderId);

            List<StopRow> target = rows.computeIfAbsent(targetRouteId, id -> new ArrayList<>());
            StopRow into = target.stream()
                    .filter(row -> !row.cancelled() && row.point.equals(candidate.location()))
                    .findFirst()
                    .orElseGet(() -> {
                        // 실물과 같다: 없으면 맨 뒤에 새로 만들고, 순번은 rewrite 가 다시 매긴다.
                        int maxSeq = target.stream().mapToInt(row -> row.seq).max().orElse(0);
                        StopRow created = new StopRow(maxSeq + 1, candidate.location(),
                                candidate.serviceSeconds(), from.arrival,
                                new ArrayList<>(), candidate.promised());
                        target.add(created);
                        return created;
                    });
            into.orderIds.add(orderId);
            // 비워진 stop 은 지운다 — 남겨 두면 seq 재부여가 유령 지점을 셈에 넣는다.
            rows.get(routeOf(from)).removeIf(row -> row.id.equals(fromStopId)
                    && row.orderIds.isEmpty());
        }

        private UUID routeOf(StopRow row) {
            return rows.entrySet().stream().filter(entry -> entry.getValue().contains(row))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
        }

        @Override
        public void rewrite(UUID routeId, PlannedRoute route) {
            summaries.put(routeId, route);
            int seq = 1;
            for (var planned : route.stops()) {
                StopRow row = rows.get(routeId).stream()
                        .filter(candidate -> !candidate.cancelled()
                                && candidate.point.equals(planned.stop().point()))
                        .findFirst().orElseThrow(() -> new IllegalStateException(
                                "다시 쓸 stop 을 찾지 못했습니다: " + planned.seq()));
                row.seq = seq++;
                row.arrival = planned.arrival();
            }
            for (StopRow row : rows.get(routeId)) {
                if (row.cancelled()) {
                    row.seq = seq++;
                }
            }
        }

        @Override
        public void clear(UUID routeId) {
            rows.put(routeId, new ArrayList<>());
        }
    }

    /** 발행 기록. */
    static final class Events implements DispatchEvents {

        final List<UUID> routesAssigned = new ArrayList<>();
        final List<UUID> ordersDispatched = new ArrayList<>();
        final List<RouteSnapshot> revised = new ArrayList<>();
        final List<Integer> revisions = new ArrayList<>();
        int completed;
        int failed;

        @Override
        public void routeAssigned(RoutePlan plan, UUID routeId, PlannedRoute route, int revision) {
            routesAssigned.add(routeId);
        }

        @Override
        public void routeRevised(RoutePlan plan, RouteSnapshot snapshot, int revision) {
            revised.add(snapshot);
            revisions.add(revision);
        }

        @Override
        public void ordersDispatched(UUID routeId, List<UUID> orderIds) {
            ordersDispatched.addAll(orderIds);
        }

        @Override
        public void planCompleted(RoutePlan plan, PlanResult result) {
            completed++;
        }

        @Override
        public void planFailed(RoutePlan plan) {
            failed++;
        }
    }
}
