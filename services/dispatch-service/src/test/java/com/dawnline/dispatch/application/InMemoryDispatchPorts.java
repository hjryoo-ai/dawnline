package com.dawnline.dispatch.application;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.DispatchEvents;
import com.dawnline.dispatch.application.port.out.PlannedRouteRepository;
import com.dawnline.dispatch.application.port.out.RoutePlanRepository;
import com.dawnline.dispatch.application.port.out.RuleCatalog;
import com.dawnline.dispatch.application.port.out.VehicleCatalog;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.PlanStatus;
import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.Capacity;
import com.dawnline.dispatch.domain.optimizer.Explanation;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
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

    static CampLocator camps(UUID campId) {
        return id -> new CampDepot(campId, CAMP);
    }

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

    /** 발행 기록. */
    static final class Events implements DispatchEvents {

        final List<UUID> routesAssigned = new ArrayList<>();
        final List<UUID> ordersDispatched = new ArrayList<>();
        int completed;
        int failed;

        @Override
        public void routeAssigned(RoutePlan plan, UUID routeId, PlannedRoute route, int revision) {
            routesAssigned.add(routeId);
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
