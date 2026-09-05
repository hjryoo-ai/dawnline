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
        public List<RoutePlan> findStalePlanning(Instant startedBefore, int limit) {
            return byId.values().stream()
                    .filter(plan -> plan.status() == PlanStatus.PLANNING)
                    .filter(plan -> plan.startedAt().map(at -> at.isBefore(startedBefore)).orElse(false))
                    .limit(limit).toList();
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

        /** 시각을 다시 쓴 결과. {@code null} 이면 살아 있는 stop 이 하나도 없었다는 뜻이다. */
        final Map<UUID, PlannedRoute> retimed = new LinkedHashMap<>();

        CancellableRoutes(Candidates candidates) {
            this.candidates = candidates;
        }

        /** {@code route_stops} 한 행. */
        static final class StopRow {

            final UUID id = Ids.newId();
            final int seq;
            final GeoPoint point;
            final int serviceSeconds;
            final List<UUID> orderIds;
            Instant arrival;
            String status = "PLANNED";

            StopRow(int seq, GeoPoint point, int serviceSeconds, Instant arrival,
                    List<UUID> orderIds) {
                this.seq = seq;
                this.point = point;
                this.serviceSeconds = serviceSeconds;
                this.arrival = arrival;
                this.orderIds = List.copyOf(orderIds);
            }

            boolean cancelled() {
                return "CANCELLED".equals(status);
            }
        }

        UUID route(UUID planId, UUID vehicleId, List<StopRow> stops) {
            UUID routeId = Ids.newId();
            headers.put(routeId, new RouteHeader(routeId, planId, vehicleId));
            rows.put(routeId, new ArrayList<>(stops));
            revisions.put(routeId, 1);
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
        public List<Stop> loadStops(UUID routeId) {
            List<Stop> live = new ArrayList<>();
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
                live.add(new Stop(row.point,
                        alive.stream().map(candidate -> OrderId.of(candidate.orderId())).toList(),
                        parcel, alive.getFirst().promised(), row.serviceSeconds,
                        alive.stream().mapToInt(DispatchCandidate::priority).max().orElse(0)));
            }
            return live;
        }

        @Override
        public Optional<AssignedStop> findAssignedStop(UUID orderId) {
            return rows.entrySet().stream()
                    .flatMap(entry -> entry.getValue().stream()
                            .filter(row -> row.orderIds.contains(orderId))
                            .map(row -> new AssignedStop(entry.getKey(), row.id, row.status)))
                    .findFirst();
        }

        @Override
        public boolean cancelStopIfAllOrdersCancelled(UUID stopId) {
            StopRow row = rows.values().stream().flatMap(List::stream)
                    .filter(stop -> stop.id.equals(stopId)).findFirst().orElseThrow();
            if (!"PLANNED".equals(row.status)) {
                return false;
            }
            boolean allDead = row.orderIds.stream().map(candidates::findById)
                    .flatMap(Optional::stream)
                    .allMatch(candidate -> candidate.status() == CandidateStatus.CANCELLED);
            if (allDead) {
                row.status = "CANCELLED";
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
                            row.cancelled()))
                    .toList();
            return Optional.of(new RouteSnapshot(routeId, header.vehicleId(),
                    summary == null ? 0 : summary.distanceM(),
                    summary == null ? 0 : summary.durationS(),
                    summary == null ? 0L : summary.cost().krw(), stops));
        }

        @Override
        public int bumpRevision(UUID routeId) {
            return revisions.merge(routeId, 1, Integer::sum);
        }

        @Override
        public Optional<UUID> findStopOf(UUID routeId, UUID orderId) {
            throw new UnsupportedOperationException("재배정은 ReassignStopServiceTest 가 본다");
        }

        @Override
        public void moveOrder(UUID fromStopId, UUID orderId, UUID targetRouteId) {
            throw new UnsupportedOperationException("재배정은 ReassignStopServiceTest 가 본다");
        }

        @Override
        public void rewrite(UUID routeId, PlannedRoute route) {
            throw new UnsupportedOperationException("취소는 rewrite 하지 않는다 — retime 이다 (§6.10)");
        }

        @Override
        public void clear(UUID routeId) {
            throw new UnsupportedOperationException("취소는 라우트를 비우지 않는다 (§6.10)");
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
