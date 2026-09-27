package com.dawnline.dispatch.application;

import com.dawnline.common.error.NotFoundException;
import com.dawnline.common.fleet.FleetFeasibility;
import com.dawnline.dispatch.application.port.in.AssessFleetUseCase;
import com.dawnline.dispatch.application.port.in.ResourceViews;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.ReferenceAdmin;
import com.dawnline.dispatch.application.port.out.RoutePlanRepository;
import com.dawnline.dispatch.application.port.out.RuleCatalog;
import com.dawnline.dispatch.application.port.out.VehicleCatalog;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.DispatchErrorCode;
import com.dawnline.dispatch.domain.PlanStatus;
import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.dispatch.domain.optimizer.Candidate;
import com.dawnline.dispatch.domain.optimizer.StopMerger;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import com.dawnline.dispatch.domain.optimizer.WaveFleet;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.annotation.Transactional;

/**
 * 웨이브의 함대 실현 가능성 (DESIGN.md §5.3 「함대」, ADR-067 결정 2).
 *
 * <p>계획이 볼 것을 잰다 — 후보는 계획의 질의({@code findPlannableInWave})와 변환({@link OptimizerCandidates})을 그대로 쓰고,
 * {@link StopMerger} 를 한 번 돌린 <strong>통합 후 stop</strong> 이다. 차량은 계획이 받는 함대({@link VehicleCatalog#availableAt},
 * 활성만 · 지금 시각에 붙인 근무창) 가운데 이 웨이브가 쓸 수 있는 것 — <strong>계획과 같은 함수</strong>({@link WaveFleet#usable},
 * ADR-039 후속)다. 주간조를 새벽 웨이브의 용량으로 세면 부족분이 조용히 사라지고, 기준과 계획이 집합을 따로 적으면 「기준이 센
 * 차량」과 「계획이 쓴 차량」이 갈라진다.
 */
public class AssessFleetService implements AssessFleetUseCase {

    private final DispatchCandidateRepository candidates;
    private final RoutePlanRepository plans;
    private final VehicleCatalog vehicles;
    private final RuleCatalog rules;
    private final ReferenceAdmin admin;
    private final Clock clock;

    /**
     * @param candidates 후보
     * @param plans      계획 — 발행된 것이 있으면 잴 것이 없다
     * @param vehicles   계획이 받는 함대
     * @param rules      룰 — stop 축의 상한({@code max-stops})
     * @param admin      차량 행 — 템플릿의 코드 · 비용 · 근무 시각
     * @param clock      지금(불변규칙 12) — 근무창을 붙이는 기준
     */
    public AssessFleetService(DispatchCandidateRepository candidates, RoutePlanRepository plans,
            VehicleCatalog vehicles, RuleCatalog rules, ReferenceAdmin admin, Clock clock) {
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.vehicles = Objects.requireNonNull(vehicles, "vehicles");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.admin = Objects.requireNonNull(admin, "admin");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(readOnly = true)
    public ResourceViews.FleetFeasibilityView assess(UUID waveId) {
        Objects.requireNonNull(waveId, "waveId");
        @Nullable RoutePlan plan = plans.findByWaveId(waveId).orElse(null);
        if (plan != null && plan.status() == PlanStatus.PUBLISHED) {
            throw DispatchErrorCode.waveAlreadyPlanned(waveId, plan.id());
        }
        List<DispatchCandidate> plannable = candidates.findPlannableInWave(waveId);
        if (plannable.isEmpty()) {
            throw NotFoundException.of("WaveCandidates", waveId.toString());
        }
        UUID campId = plannable.getFirst().campId();
        Instant now = clock.instant();

        List<Candidate> optimizerCandidates = OptimizerCandidates.of(plannable);
        Map<UUID, ResourceViews.VehicleView> rows = admin.listVehicles(campId).stream()
                .collect(Collectors.toMap(ResourceViews.VehicleView::id, Function.identity()));
        List<FleetFeasibility.Vehicle> fleet = new ArrayList<>();
        for (VehicleSpec spec : WaveFleet.usable(vehicles.availableAt(campId, now), optimizerCandidates)) {
            ResourceViews.VehicleView row = rows.get(spec.id().value());
            if (row == null) {
                continue;
            }
            fleet.add(new FleetFeasibility.Vehicle(row.id().toString(), row.code(), row.cold(), row.allowsHazmat(),
                    row.maxWeightG(), row.maxVolumeCm3(), row.fixedCostKrw()));
        }

        List<FleetFeasibility.Stop> stops = StopMerger.merge(optimizerCandidates).stream()
                .map(stop -> new FleetFeasibility.Stop(stop.parcel().requiresCold(), stop.parcel().hazmat(),
                        stop.parcel().weightG(), stop.parcel().volumeCm3()))
                .toList();
        OptionalInt cap = rules.forCamp(campId).routeStopCap();
        @Nullable Integer maxStops = cap.isPresent() ? cap.getAsInt() : null;

        FleetFeasibility.Assessment assessment = FleetFeasibility.assess(stops, fleet, maxStops);
        List<ResourceViews.FleetLineView> lines = assessment.lines().stream()
                .map(line -> line(line, rows))
                .toList();
        return new ResourceViews.FleetFeasibilityView(waveId, campId, now, plannable.size(), assessment.stops(),
                fleet.size(), maxStops, FleetFeasibility.HEADROOM_PERCENT, assessment.feasible(), lines);
    }

    private static ResourceViews.FleetLineView line(FleetFeasibility.Line line,
            Map<UUID, ResourceViews.VehicleView> rows) {
        FleetFeasibility.Combination combination = line.combination();
        ResourceViews.@Nullable VehicleView template = line.template() == null
                ? null : rows.get(UUID.fromString(line.template().id()));
        return new ResourceViews.FleetLineView(combination.cold(), combination.hazmat(), combination.large(),
                combination.label(), line.status(), line.demand().stops(), line.demand().weightG(),
                line.demand().volumeCm3(), line.vehicles(), line.capacity().stops(), line.capacity().weightG(),
                line.capacity().volumeCm3(), line.shortfall(), template);
    }
}
