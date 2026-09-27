package com.dawnline.sim.fleet;

import com.dawnline.sim.config.SimProperties.Scenario.Fleet;
import com.dawnline.sim.fleet.OpsClient.Assessment;
import com.dawnline.sim.fleet.OpsClient.Line;
import com.dawnline.sim.fleet.OpsClient.NewVehicle;
import com.dawnline.sim.fleet.OpsClient.OpsException;
import com.dawnline.sim.fleet.FleetReport.Action;
import com.dawnline.sim.fleet.OpsClient.Reply;
import com.dawnline.sim.fleet.OpsClient.RouteStop;
import com.dawnline.sim.fleet.OpsClient.RouteSummary;
import com.dawnline.sim.fleet.OpsClient.Vehicle;
import com.dawnline.sim.fleet.OpsClient.Wave;
import com.dawnline.sim.order.Sleeper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 성수기 증차 — 운영자가 하는 일을 그대로 (DESIGN.md §5.6 「성수기 증차」, ADR-067).
 *
 * <p>한 실행이 {@link Session} 하나다. 순서가 곧 규칙이다:
 * <ol>
 *   <li>{@link Session#requireNoLeftovers()} — 시작 전 활성 {@code peak-sim} 0 (결정 6 의 전제 어설션).</li>
 *   <li>{@link Session#provision(Instant)} — 창의 DAWN 웨이브마다 dispatch 의 판정을 읽고, {@code feasible} 이면 부족 대수만큼
 *       템플릿을 더한다. 부족한데 템플릿이 없는 조합이 하나라도 있으면 <strong>더하지 않고</strong> 실패한다(결정 3).
 *       {@code feasible} 이면 그 캠프의 증차가 끝난 <strong>직후</strong> 그 웨이브를 조기 마감한다(결정 9 — 시각이 아니라 순서).</li>
 *   <li>{@link Session#awaitPlans()} — 창의 웨이브가 전부 계획되기를 기다리고, 증차가 그 웨이브의 {@code closed_at} 보다 먼저
 *       끝났는지 본다(결정 7 — 시간 예산은 어설션이다).</li>
 *   <li>{@link Session#reassign()} — 조기 마감한 웨이브마다 한 번 운영자의 재배정(결정 9). 409 는 기사가 먼저 닿은 것이라 세기만
 *       한다.</li>
 *   <li>{@link Session#release()} — <strong>이번 실행이 더한 차량만</strong> 비활성화한다. 409 {@code vehicle-in-service} 는 기사가
 *       끝낸 stop 을 dispatch 가 소비하기까지의 지연이라 상한 안에서 다시 시도한다(결정 5 · 6).</li>
 *   <li>{@link Session#verifyAudit()} — 받은 감사 행을 기대와 대조한다: 캠프마다 증차 N + 조기 마감 1 + 재배정 1, 비활성화는 성공
 *       수 = 더한 대수.</li>
 * </ol>
 *
 * <p>시각은 주입 시계다 — 서비스 다섯과 같은 오프셋이라 증차 완료 시각과 {@code closed_at} 을 한 축에서 비교한다(ADR-066).
 */
public final class PeakFleet {

    private static final Logger log = LoggerFactory.getLogger(PeakFleet.class);

    /** 증차한 차량의 출처 — V11 의 닫힌 집합(ADR-067 결정 4). */
    static final String SOURCE = "peak-sim";

    /** 조기 마감의 이유 — 코어가 필수로 받는다(ADR-054 결정 2). 실행 표지를 싣는다. */
    static final String CLOSE_REASON = "성수기 증차 완료 — 컷오프 + grace 를 기다리지 않고 계획을 앞당긴다 (peak-sim %s)";

    /** 창의 DAWN 웨이브를 찾는 컷오프 창의 반폭 — 컷오프는 정확히 같아야 하고, 이 폭은 조회의 범위일 뿐이다. */
    private static final Duration CUTOFF_SPAN = Duration.ofHours(1);

    private final OpsClient ops;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration poll;
    private final Duration planTimeout;
    private final Duration releaseTimeout;
    private final Supplier<String> runTag;

    /**
     * @param ops            ops-api
     * @param clock          주입 시계 — 증차 완료 시각
     * @param sleeper        대기
     * @param poll           웨이브 상태 · 비활성화 재시도의 간격
     * @param planTimeout    창의 웨이브가 전부 계획되기를 기다리는 상한
     * @param releaseTimeout 비활성화 409 를 다시 시도하는 상한
     * @param runTag         실행 표지 — 증차 차량의 코드에 들어간다(코드는 전역 UNIQUE 이고 앞 실행의 비활성 차량이 남아 있다)
     */
    public PeakFleet(OpsClient ops, Clock clock, Sleeper sleeper, Duration poll, Duration planTimeout,
            Duration releaseTimeout, Supplier<String> runTag) {
        this.ops = Objects.requireNonNull(ops, "ops");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.poll = Objects.requireNonNull(poll, "poll");
        this.planTimeout = Objects.requireNonNull(planTimeout, "planTimeout");
        this.releaseTimeout = Objects.requireNonNull(releaseTimeout, "releaseTimeout");
        this.runTag = Objects.requireNonNull(runTag, "runTag");
    }

    /**
     * @param mode 증차하는가
     * @return 한 실행
     */
    public Session open(Fleet mode) {
        return new Session(Objects.requireNonNull(mode, "mode"), runTag.get());
    }

    /** 한 실행. 상태를 들고 리포트를 낸다. 스레드 하나에서 쓴다. */
    public final class Session {

        private final Fleet mode;
        private final String tag;
        private final Map<UUID, Wave> waves = new LinkedHashMap<>();
        private final Map<UUID, Assessment> assessments = new LinkedHashMap<>();
        private final Map<UUID, Integer> addedByWave = new LinkedHashMap<>();
        private final Map<UUID, Instant> provisionedAt = new LinkedHashMap<>();
        private final List<FleetReport.Row> rows = new ArrayList<>();
        private final List<UUID> added = new ArrayList<>();
        private final Set<String> auditIds = new LinkedHashSet<>();
        private final List<String> failures = new ArrayList<>();
        /** 캠프 × 커맨드 — 기대한 감사 행 수. 비활성화는 성공 수의 기대다(더한 대수). */
        private final Map<UUID, Map<Action, Integer>> expected = new LinkedHashMap<>();
        /** 캠프 × 커맨드 — 받은 감사 id 수. 비활성화는 시도 전부다(409 재시도 포함). */
        private final Map<UUID, Map<Action, Integer>> received = new LinkedHashMap<>();
        private final Map<UUID, Integer> deactivatedByCamp = new LinkedHashMap<>();
        private final Map<UUID, UUID> campOfVehicle = new LinkedHashMap<>();
        private final List<UUID> closedEarly = new ArrayList<>();
        private final List<FleetReport.Reassign> reassigns = new ArrayList<>();
        private int deactivated;
        private int sequence;

        private Session(Fleet mode, String tag) {
            this.mode = mode;
            this.tag = tag;
        }

        /**
         * 시작 전 전제 — 읽기 모델이 아는 캠프 전부에서 활성 {@code peak-sim} 0.
         *
         * <p>새 스택이면 캠프 목록이 비어 있고 그것이 맞다 — {@code peak-sim} 차량은 웨이브가 있는 캠프에만 더해지고 그 웨이브는
         * {@code rm_waves} 에 90일 남는다(ADR-058). 증차 직전에 창의 캠프로 한 번 더 센다({@link #provision}).
         *
         * @throws FleetFailure 남은 차량이 있다
         */
        public void requireNoLeftovers() {
            guarded(() -> requireNoLeftovers("시작 전", ops.camps()));
        }

        private void requireNoLeftovers(String when, Collection<UUID> camps) {
            List<String> leftovers = new ArrayList<>();
            for (UUID camp : camps) {
                for (Vehicle vehicle : ops.vehicles(camp)) {
                    if (vehicle.active() && SOURCE.equals(vehicle.source())) {
                        leftovers.add(vehicle.code());
                    }
                }
            }
            if (!leftovers.isEmpty()) {
                fail(("전제(%s): 활성 peak-sim 차량 %d대 %s — 앞 실행이 비활성화하지 못하고 남긴 차량이다. 이번 실행의 함대에 섞이면 "
                        + "overload-day 는 과부하가 아니고 peak-day 의 계산은 그 차량을 이미 센다")
                        .formatted(when, leftovers.size(), leftovers));
            }
            log.info("전제({}): 캠프 {}곳, 활성 peak-sim 0", when, camps.size());
        }

        /**
         * 창의 DAWN 웨이브마다 판정을 읽고, {@code feasible} 이면 더한다.
         *
         * @param cutoff 창 끝 다음의 DAWN 컷오프
         * @throws FleetFailure 웨이브가 없다 · 남은 차량 · 이미 계획됨 · 템플릿 없음 · 등록 거절
         */
        public void provision(Instant cutoff) {
            Objects.requireNonNull(cutoff, "cutoff");
            guarded(() -> provisionUnguarded(cutoff));
        }

        private void provisionUnguarded(Instant cutoff) {
            for (UUID camp : ops.camps()) {
                for (Wave wave : ops.waves(camp, cutoff.minus(CUTOFF_SPAN), cutoff.plus(CUTOFF_SPAN))) {
                    if ("DAWN".equals(wave.serviceTier()) && cutoff.equals(wave.cutoffAt())) {
                        waves.put(wave.waveId(), wave);
                    }
                }
            }
            if (waves.isEmpty()) {
                fail(("창의 DAWN 웨이브(컷오프 %s)가 읽기 모델에 없다 — 주문이 접수되지 않았거나 fulfillment.planned 가 아직 "
                        + "투영되지 않았다")
                        .formatted(cutoff));
            }
            List<UUID> camps = new ArrayList<>();
            for (UUID waveId : waves.keySet()) {
                Assessment assessment;
                try {
                    assessment = ops.fleetFeasibility(waveId);
                } catch (OpsException e) {
                    if ("wave-already-planned".equals(e.code())) {
                        fail(("시간 예산: 웨이브 %s 의 계획이 증차 전에 이미 발행됐다 — 창 끝(23:58)과 마감(컷오프 + grace) 사이가 "
                                + "모자랐다")
                        .formatted(waveId));
                    }
                    throw e;
                }
                assessments.put(waveId, assessment);
                camps.add(assessment.campId());
            }
            requireNoLeftovers("증차 직전", camps);

            List<String> missing = new ArrayList<>();
            assessments.values().forEach(assessment -> assessment.combinations().stream().filter(Line::noTemplate)
                    .forEach(line -> missing.add(short8(assessment.campId()) + " " + line.label())));
            if (mode == Fleet.FEASIBLE && !missing.isEmpty()) {
                fail("템플릿 없음: %s — 부족한데 그 캠프 · 교대에 그 능력의 차량이 없다. 대수를 잴 단위가 없어 더하지 않았다"
                        .formatted(missing));
            }

            for (Assessment assessment : assessments.values()) {
                int count = 0;
                for (Line line : assessment.combinations()) {
                    int toAdd = mode == Fleet.FEASIBLE ? line.toAdd() : 0;
                    for (int i = 0; i < toAdd; i++) {
                        add(assessment.campId(), Objects.requireNonNull(line.template(), "SHORTFALL 에는 템플릿이 있다"));
                    }
                    count += toAdd;
                    rows.add(new FleetReport.Row(assessment.campId(), line.label(), line.status(), line.shortfall(),
                            toAdd));
                }
                addedByWave.put(assessment.waveId(), count);
                provisionedAt.put(assessment.waveId(), clock.instant());
                if (mode == Fleet.FEASIBLE) {
                    expect(assessment.campId(), Action.ADD_VEHICLE, count);
                    expect(assessment.campId(), Action.DEACTIVATE_VEHICLE, count);
                    closeEarly(assessment);
                }
            }
            log.info("증차: 웨이브 {}개, 더한 차량 {}대, 조기 마감 {} ({})", waves.size(), added.size(), closedEarly.size(),
                    mode);
        }

        private void add(UUID campId, Vehicle template) {
            String code = "PS%s-%04d".formatted(tag, ++sequence);
            Reply reply = ops.addVehicle(NewVehicle.copyOf(template, code, SOURCE));
            record(campId, Action.ADD_VEHICLE, reply);
            if (!reply.ok() || reply.id() == null) {
                fail("증차 거절: %s — HTTP %d %s (감사 %s)".formatted(code, reply.status(), reply.code(), reply.auditId()));
            }
            UUID vehicleId = Objects.requireNonNull(reply.id());
            added.add(vehicleId);
            campOfVehicle.put(vehicleId, campId);
        }

        /**
         * 증차가 끝난 직후 그 웨이브를 닫는다 — 운영자는 물량을 보고 증차한 <strong>뒤에</strong> 닫는다(결정 9). 그래서
         * {@code closed_at} 이 증차 완료보다 뒤인 것이 구조다. 409 {@code wave-not-open} 은 그 구조가 깨진 날이다 — 스케줄러가
         * 먼저 닫았고, 계획이 그 차량을 못 봤을 수 있다.
         */
        private void closeEarly(Assessment assessment) {
            expect(assessment.campId(), Action.CLOSE_WAVE, 1);
            Reply reply = ops.closeWave(assessment.waveId(), CLOSE_REASON.formatted(tag));
            record(assessment.campId(), Action.CLOSE_WAVE, reply);
            if (reply.ok()) {
                closedEarly.add(assessment.waveId());
                return;
            }
            if (reply.status() == 409 && "wave-not-open".equals(reply.code())) {
                fail(("시간 예산: 웨이브 %s 를 증차 직후 닫으려 했는데 이미 닫혀 있다 — 스케줄러가 먼저 닫았다(증차가 컷오프 + grace 를 "
                        + "넘겼다). 계획이 그 차량을 못 봤을 수 있다 (감사 %s)").formatted(short8(assessment.waveId()),
                        reply.auditId()));
            }
            fail("조기 마감 거절: 웨이브 %s — HTTP %d %s (감사 %s)".formatted(short8(assessment.waveId()), reply.status(),
                    reply.code(), reply.auditId()));
        }

        /**
         * 창의 웨이브가 전부 계획되기를 기다리고, 시간 예산을 본다.
         *
         * @throws FleetFailure 계획 실패 · 상한 초과 · 증차가 마감 뒤에 끝났다 · 마감 시각을 모른다
         * @throws InterruptedException 대기 중 인터럽트
         */
        public void awaitPlans() throws InterruptedException {
            long deadline = planTimeout.toNanos();
            while (true) {
                guarded(this::refreshWaves);
                List<Wave> failed = waves.values().stream().filter(w -> "PLAN_FAILED".equals(w.status())).toList();
                if (!failed.isEmpty()) {
                    fail("계획 실패: 웨이브 %s — plan.failed. 사유는 dispatch 의 계획 행(§6.7 mode_reason)과 로그"
                            .formatted(failed.stream().map(w -> short8(w.waveId())).toList()));
                }
                if (waves.values().stream().allMatch(w -> "PLANNED".equals(w.status()))) {
                    break;
                }
                if (deadline <= 0) {
                    fail("계획 대기: %d초 안에 창의 웨이브 %d개가 전부 PLANNED 가 되지 않았다 — 남은 것 %s".formatted(
                            planTimeout.toSeconds(), waves.size(), waves.values().stream()
                                    .filter(w -> !"PLANNED".equals(w.status()))
                                    .map(w -> short8(w.waveId()) + "=" + w.status()).toList()));
                }
                sleeper.sleepNanos(poll.toNanos());
                deadline -= poll.toNanos();
            }
            for (Wave wave : waves.values()) {
                if (addedByWave.getOrDefault(wave.waveId(), 0) == 0) {
                    continue;
                }
                Instant done = provisionedAt.get(wave.waveId());
                if (wave.closedAt() == null) {
                    fail("시간 예산: 웨이브 %s 의 closed_at 을 모른다 — 증차가 마감 전에 끝났는지 말할 수 없다".formatted(
                            short8(wave.waveId())));
                }
                if (done == null || !done.isBefore(wave.closedAt())) {
                    fail("시간 예산: 웨이브 %s 의 증차가 %s 에 끝났는데 마감은 %s 다 — 계획이 그 차량을 못 봤을 수 있다".formatted(
                            short8(wave.waveId()), done, wave.closedAt()));
                }
            }
        }

        /**
         * 조기 마감한 웨이브마다 한 번 재배정한다 — 가장 많이 실은 라우트의 <strong>마지막</strong> {@code PLANNED} stop 을 가장 적게
         * 실은 라우트로(결정 9). 마지막 stop 은 기사가 가장 늦게 닿는 곳이라 경합이 가장 작고, 가장 적게 실은 쪽은 용량 위반이
         * 가장 드물다.
         *
         * <p>409 는 세기만 한다 — {@code stop-not-planned} 는 기사가 먼저 닿은 것이고(「늦었다」), {@code conflict} 는 옮기면 하드
         * 룰을 어기는 것이다. 운영자에게도 둘은 도구의 결함이 아니다. 그 밖의 거절은 실패다. 옮길 곳이 없으면(라우트 둘 미만 ·
         * stop 둘 이상인 라우트 없음) 보내지 않고 그 이유를 리포트에 남긴다 — 기대도 0 이다.
         *
         * @throws FleetFailure 409 가 아닌 거절 · 읽기 모델이 라우트를 상한 안에 내지 않았다
         * @throws InterruptedException 대기 중 인터럽트
         */
        public void reassign() throws InterruptedException {
            for (UUID waveId : closedEarly) {
                Wave wave = waves.get(waveId);
                UUID camp = assessments.get(waveId).campId();
                List<RouteSummary> routes = awaitRoutes(waveId, wave == null || wave.routeCount() == null
                        ? 0 : wave.routeCount());
                List<RouteSummary> byLoad = routes.stream().filter(route -> route.stopCount() != null)
                        .sorted(java.util.Comparator.comparingInt(route -> Objects.requireNonNull(route.stopCount())))
                        .toList();
                if (byLoad.size() < 2 || Objects.requireNonNull(byLoad.getLast().stopCount()) < 2) {
                    skipReassign(camp, waveId, "라우트 %d 개, 가장 많이 실은 것의 stop %s 개 — 옮길 곳이 없다".formatted(byLoad.size(),
                            byLoad.isEmpty() ? "0" : byLoad.getLast().stopCount()));
                    continue;
                }
                RouteSummary from = byLoad.getLast();
                RouteSummary to = byLoad.getFirst();
                List<RouteStop> stops = new ArrayList<>(guardedRead(() -> ops.routeStops(from.routeId())));
                java.util.Collections.reverse(stops);
                RouteStop last = stops.stream().filter(stop -> "PLANNED".equals(stop.status()) && !stop.orderIds().isEmpty())
                        .findFirst().orElse(null);
                if (last == null) {
                    skipReassign(camp, waveId, "가장 많이 실은 라우트에 PLANNED stop 이 없다 — 기사가 이미 다 돌았다");
                    continue;
                }
                expect(camp, Action.REASSIGN_STOP, 1);
                Reply reply = ops.reassign(from.routeId(), last.orderIds().getFirst(), to.routeId());
                record(camp, Action.REASSIGN_STOP, reply);
                if (!reply.ok() && reply.status() != 409) {
                    fail("재배정 거절: 웨이브 %s — HTTP %d %s (감사 %s). 409 가 아닌 거절은 도구 쪽이다".formatted(
                            short8(waveId), reply.status(), reply.code(), reply.auditId()));
                }
                reassigns.add(new FleetReport.Reassign(camp, waveId, reply.status(), reply.code(), null));
            }
        }

        private void skipReassign(UUID camp, UUID waveId, String why) {
            expect(camp, Action.REASSIGN_STOP, 0);
            reassigns.add(new FleetReport.Reassign(camp, waveId, null, null, why));
        }

        /** 읽기 모델의 라우트가 계획의 수만큼 투영되기를 기다린다 — {@code plan.completed} 와 {@code route.assigned} 는 다른 사실이다. */
        private List<RouteSummary> awaitRoutes(UUID waveId, int expectedRoutes) throws InterruptedException {
            long deadline = planTimeout.toNanos();
            while (true) {
                List<RouteSummary> routes = guardedRead(() -> ops.waveRoutes(waveId));
                if (routes.size() >= expectedRoutes) {
                    return routes;
                }
                if (deadline <= 0) {
                    fail("재배정: 웨이브 %s 의 라우트가 %d초 안에 %d 개 중 %d 개만 읽기 모델에 있다".formatted(short8(waveId),
                            planTimeout.toSeconds(), expectedRoutes, routes.size()));
                }
                sleeper.sleepNanos(poll.toNanos());
                deadline -= poll.toNanos();
            }
        }

        /**
         * 받은 감사 행을 기대와 대조한다 — 캠프마다 증차 N(판정의 부족분 합) + 조기 마감 1 + 재배정 1(옮길 곳이 없으면 0). 비활성화는
         * 409 재시도 수가 정해져 있지 않아 행 수가 아니라 <strong>성공 수 = 더한 대수</strong>로 본다. 앞 단계가 실패했으면 대조하지
         * 않는다 — 멈춘 실행의 수는 기대와 다른 것이 당연하고, 실패는 이미 리포트에 있다.
         *
         * @throws FleetFailure 기대와 다르다
         */
        public void verifyAudit() {
            if (mode != Fleet.FEASIBLE || !failures.isEmpty()) {
                return;
            }
            List<String> mismatches = new ArrayList<>();
            expected.forEach((camp, actions) -> actions.forEach((action, want) -> {
                int got = action == Action.DEACTIVATE_VEHICLE ? deactivatedByCamp.getOrDefault(camp, 0)
                        : received.getOrDefault(camp, Map.of()).getOrDefault(action, 0);
                if (got != want) {
                    mismatches.add("%s %s 기대 %d · %s %d".formatted(short8(camp), action, want,
                            action == Action.DEACTIVATE_VEHICLE ? "성공" : "받은 감사 id", got));
                }
            }));
            if (!mismatches.isEmpty()) {
                fail("감사 행이 기대와 다르다: %s".formatted(mismatches));
            }
        }

        private void expect(UUID camp, Action action, int count) {
            expected.computeIfAbsent(camp, key -> new java.util.EnumMap<>(Action.class)).merge(action, count, Integer::sum);
        }

        private <T> T guardedRead(Supplier<T> read) {
            try {
                return read.get();
            } catch (OpsException e) {
                fail("ops-api: " + e.getMessage());
                throw e;
            }
        }

        private void refreshWaves() {
            Set<UUID> camps = new LinkedHashSet<>();
            assessments.values().forEach(assessment -> camps.add(assessment.campId()));
            for (UUID camp : camps) {
                Instant cutoff = Objects.requireNonNull(waves.values().iterator().next().cutoffAt());
                for (Wave wave : ops.waves(camp, cutoff.minus(CUTOFF_SPAN), cutoff.plus(CUTOFF_SPAN))) {
                    if (waves.containsKey(wave.waveId())) {
                        waves.put(wave.waveId(), wave);
                    }
                }
            }
        }

        /** @return 창의 웨이브 — 기사가 셀 것 */
        public Set<UUID> waveIds() {
            return Set.copyOf(waves.keySet());
        }

        /** @return 창의 웨이브들의 라우트 수 합 — 기사가 기다릴 수. 계획 뒤에만 뜻이 있다 */
        public int routes() {
            return waves.values().stream().mapToInt(w -> w.routeCount() == null ? 0 : w.routeCount()).sum();
        }

        /**
         * 이번 실행이 더한 차량만 비활성화한다.
         *
         * @throws FleetFailure 상한 안에 비활성화하지 못한 차량이 있다
         * @throws InterruptedException 대기 중 인터럽트
         */
        public void release() throws InterruptedException {
            List<UUID> left = new ArrayList<>(added);
            long budget = releaseTimeout.toNanos();
            Map<UUID, String> last = new LinkedHashMap<>();
            while (!left.isEmpty()) {
                List<UUID> next = new ArrayList<>();
                for (UUID vehicleId : left) {
                    Reply reply = deactivate(vehicleId);
                    UUID camp = campOfVehicle.get(vehicleId);
                    record(camp, Action.DEACTIVATE_VEHICLE, reply);
                    if (reply.ok()) {
                        deactivated++;
                        if (camp != null) {
                            deactivatedByCamp.merge(camp, 1, Integer::sum);
                        }
                    } else if (reply.status() == 409 && "vehicle-in-service".equals(reply.code())) {
                        next.add(vehicleId);
                        last.put(vehicleId, reply.code());
                    } else {
                        fail("비활성화 거절: 차량 %s — HTTP %d %s (감사 %s)".formatted(vehicleId, reply.status(),
                                reply.code(), reply.auditId()));
                    }
                }
                left = next;
                if (left.isEmpty()) {
                    break;
                }
                if (budget <= 0) {
                    fail(("비활성화: %d초 안에 %d대가 끝나지 않은 stop 을 들고 있다(%s) — 기사가 라우트를 끝내지 못했거나 dispatch 가 "
                            + "delivery.status 를 아직 소비하지 않았다. 남은 차량은 다음 실행의 전제가 잡는다")
                        .formatted(releaseTimeout.toSeconds(), left.size(), left));
                }
                sleeper.sleepNanos(poll.toNanos());
                budget -= poll.toNanos();
            }
            log.info("비활성화: peak-sim {}대", deactivated);
        }

        private Reply deactivate(UUID vehicleId) {
            try {
                return ops.deactivate(vehicleId);
            } catch (OpsException e) {
                fail("비활성화: " + e.getMessage());
                throw e;
            }
        }

        /**
         * @return 창의 웨이브가 전부 계획됐다 — 기사가 기다릴 수({@link #routes()})가 뜻을 갖는다. 시간 예산이 어긋나도 참일 수 있다
         */
        public boolean planned() {
            return !waves.isEmpty() && waves.values().stream().allMatch(w -> "PLANNED".equals(w.status()));
        }

        /** @return 더한 차량 수 */
        public int addedCount() {
            return added.size();
        }

        /** @return 지금까지의 리포트 — 실패 뒤에도 낸다 */
        public FleetReport report() {
            List<FleetReport.WaveLine> lines = new ArrayList<>();
            for (Wave wave : waves.values()) {
                Assessment assessment = assessments.get(wave.waveId());
                lines.add(new FleetReport.WaveLine(assessment == null ? null : assessment.campId(), wave.waveId(),
                        assessment == null ? null : assessment.candidates(), wave.orderCount(), wave.unassignedCount(),
                        wave.routeCount(), addedByWave.getOrDefault(wave.waveId(), 0),
                        provisionedAt.get(wave.waveId()), wave.closedAt()));
            }
            List<FleetReport.AuditLine> audit = new ArrayList<>();
            for (Map.Entry<UUID, Map<Action, Integer>> camp : expected.entrySet()) {
                for (Action action : Action.values()) {
                    Integer want = camp.getValue().get(action);
                    if (want == null) {
                        continue;
                    }
                    audit.add(new FleetReport.AuditLine(camp.getKey(), action, want,
                            received.getOrDefault(camp.getKey(), Map.of()).getOrDefault(action, 0),
                            action == Action.DEACTIVATE_VEHICLE ? deactivatedByCamp.getOrDefault(camp.getKey(), 0) : null));
                }
            }
            return new FleetReport(mode, lines, rows, added.size(), deactivated, auditIds.size(), audit, reassigns,
                    failures);
        }

        private void record(@Nullable UUID camp, Action action, Reply reply) {
            if (reply.auditId() != null && auditIds.add(reply.auditId()) && camp != null) {
                received.computeIfAbsent(camp, key -> new java.util.EnumMap<>(Action.class)).merge(action, 1, Integer::sum);
            }
        }

        private void fail(String message) {
            failures.add(message);
            throw new FleetFailure(message);
        }

        /** ops-api 가 답하지 못한 것도 도구 쪽 결함이다 — 전제 · 기준 · 계획을 모른 채 이어 가지 않는다. */
        private void guarded(Runnable step) {
            try {
                step.run();
            } catch (OpsException e) {
                fail("ops-api: " + e.getMessage());
            }
        }
    }

    static String short8(@Nullable UUID id) {
        return id == null ? "—" : id.toString().substring(0, 8);
    }
}
