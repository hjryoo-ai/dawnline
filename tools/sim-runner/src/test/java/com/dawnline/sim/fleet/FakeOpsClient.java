package com.dawnline.sim.fleet;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * 메모리의 ops-api — 함대 단계의 테스트가 쓴다. 받은 커맨드를 기억하고, 감사 id 를 하나씩 낸다.
 */
public final class FakeOpsClient implements OpsClient {

    public static final UUID CAMP = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    public static final UUID WAVE = UUID.fromString("0199a000-0000-7000-8000-0000000000a1");

    public final List<UUID> camps = new ArrayList<>(List.of(CAMP));
    public final Map<UUID, List<Vehicle>> vehicles = new LinkedHashMap<>();
    /** 웨이브 조회의 답 — 부를 때마다 앞에서 하나씩 꺼내고, 마지막 것은 남긴다(계획 전 → 계획 뒤). */
    public final Deque<List<Wave>> waveAnswers = new ArrayDeque<>();
    public Function<UUID, Assessment> feasibility = waveId -> {
        throw new IllegalStateException("판정이 준비되지 않았다");
    };
    /** 비활성화의 답 — 차량마다 앞에서 하나씩 꺼내고, 비면 200. */
    public final Map<UUID, Deque<Integer>> deactivateStatus = new LinkedHashMap<>();

    /** 조기 마감의 답 — 비면 200. */
    public final Deque<Reply> closeAnswers = new ArrayDeque<>();
    /** 웨이브의 라우트 — 읽기 모델. */
    public List<RouteSummary> routes = List.of();
    /** 라우트의 stop. */
    public final Map<UUID, List<RouteStop>> stops = new LinkedHashMap<>();
    /** 재배정의 답 — 비면 200. */
    public final Deque<Integer> reassignStatus = new ArrayDeque<>();

    public final List<NewVehicle> addedBodies = new ArrayList<>();
    public final List<String> closeCalls = new ArrayList<>();
    public final List<List<UUID>> reassignCalls = new ArrayList<>();
    /** 커맨드의 순서 — 「증차 뒤에 마감」을 본다. */
    public final List<String> commands = new ArrayList<>();
    public final List<UUID> deactivateCalls = new ArrayList<>();
    public int campsCalls;
    private int audit;

    @Override
    public List<UUID> camps() {
        campsCalls++;
        return List.copyOf(camps);
    }

    @Override
    public List<Wave> waves(UUID campId, Instant from, Instant to) {
        List<Wave> answer = waveAnswers.size() > 1 ? waveAnswers.pollFirst() : waveAnswers.peekFirst();
        return answer == null ? List.of() : answer;
    }

    @Override
    public Assessment fleetFeasibility(UUID waveId) {
        return feasibility.apply(waveId);
    }

    @Override
    public List<Vehicle> vehicles(UUID campId) {
        return vehicles.getOrDefault(campId, List.of());
    }

    @Override
    public Reply addVehicle(NewVehicle vehicle) {
        addedBodies.add(vehicle);
        commands.add("ADD_VEHICLE");
        return new Reply(200, nextAudit(), null, UUID.nameUUIDFromBytes(vehicle.code().getBytes()));
    }

    @Override
    public Reply deactivate(UUID vehicleId) {
        deactivateCalls.add(vehicleId);
        Deque<Integer> answers = deactivateStatus.get(vehicleId);
        Integer status = answers == null || answers.isEmpty() ? 200 : answers.pollFirst();
        return status == 200 ? new Reply(200, nextAudit(), null, vehicleId)
                : new Reply(status, nextAudit(), status == 409 ? "vehicle-in-service" : "internal", null);
    }

    @Override
    public Reply closeWave(UUID waveId, String reason) {
        closeCalls.add(reason);
        commands.add("CLOSE_WAVE");
        Reply answer = closeAnswers.pollFirst();
        return answer != null ? answer : new Reply(200, nextAudit(), null, null);
    }

    @Override
    public List<RouteSummary> waveRoutes(UUID waveId) {
        return routes;
    }

    @Override
    public List<RouteStop> routeStops(UUID routeId) {
        return stops.getOrDefault(routeId, List.of());
    }

    @Override
    public Reply reassign(UUID routeId, UUID orderId, UUID targetRouteId) {
        reassignCalls.add(List.of(routeId, orderId, targetRouteId));
        commands.add("REASSIGN_STOP");
        Integer status = reassignStatus.pollFirst();
        return status == null || status == 200 ? new Reply(200, nextAudit(), null, null)
                : new Reply(status, nextAudit(), status == 409 ? "stop-not-planned" : "core-error", null);
    }

    /** 감사 id 를 하나 낸다 — 미리 정한 답에 싣는다. */
    public String audit() {
        return nextAudit();
    }

    /** 첫 등록이 만든 차량 id — 비활성화의 답을 미리 정할 때 쓴다. */
    public UUID deactivateCallsTarget() {
        return UUID.nameUUIDFromBytes(addedBodies.getFirst().code().getBytes());
    }

    private String nextAudit() {
        return "audit-" + (++audit);
    }

    // --- 픽스처 --------------------------------------------------------------------------------------------

    public static final UUID LIGHT = UUID.fromString("0199a000-0000-7000-8000-0000000000b1");
    public static final UUID HEAVY = UUID.fromString("0199a000-0000-7000-8000-0000000000b2");
    public static final UUID MIDDLE = UUID.fromString("0199a000-0000-7000-8000-0000000000b3");
    /** 가장 많이 실은 라우트의 마지막 PLANNED stop 의 주문. */
    public static final UUID LAST_PLANNED = UUID.fromString("0199a000-0000-7000-8000-0000000000c3");

    public static final UUID COLD_TRUCK_A = UUID.fromString("0199a000-0000-7000-8000-0000000000e1");
    public static final UUID COLD_TRUCK_B = UUID.fromString("0199a000-0000-7000-8000-0000000000e2");
    public static final UUID PLAIN_VAN = UUID.fromString("0199a000-0000-7000-8000-0000000000e3");

    /**
     * 계획의 라우트 셋 — 첫 peak-day 의 모양(2026-09-27): 가장 많이 실은 것(stop 넷)은 냉장 트럭이고 가장 적게 실은 것은 냉장 없는
     * 밴이다. 가장 많이 실은 라우트의 앞은 끝났고 뒤 둘은 PLANNED, 마지막 것은 이미 닿았다.
     */
    public void threeRoutes() {
        routes = List.of(new RouteSummary(MIDDLE, COLD_TRUCK_B, 2), new RouteSummary(HEAVY, COLD_TRUCK_A, 4),
                new RouteSummary(LIGHT, PLAIN_VAN, 1));
        vehicles.put(CAMP, List.of(truck(COLD_TRUCK_A), truck(COLD_TRUCK_B),
                new Vehicle(PLAIN_VAN, CAMP, "PSA1B2C3-0001", "VAN", 400_000, 1_200_000, false, false, 45_000, 600, 250,
                        LocalTime.of(23, 0), LocalTime.of(8, 0), true, "operator")));
        stops.put(HEAVY, List.of(
                new RouteStop(1, "COMPLETED", List.of(UUID.fromString("0199a000-0000-7000-8000-0000000000c1"))),
                new RouteStop(2, "PLANNED", List.of(UUID.fromString("0199a000-0000-7000-8000-0000000000c2"))),
                new RouteStop(3, "PLANNED", List.of(LAST_PLANNED)),
                new RouteStop(4, "ARRIVED", List.of(UUID.fromString("0199a000-0000-7000-8000-0000000000c4")))));
    }

    /** 시드 야간조 모양의 냉장 트럭. */
    public static Vehicle truck(UUID id) {
        return new Vehicle(id, CAMP, "T-" + id.toString().substring(34), "TRUCK", 1_000_000, 4_000_000, true, false,
                80_000, 900, 300, LocalTime.of(23, 0), LocalTime.of(8, 0), true, "seed");
    }

    /** 시드 야간조 모양의 밴(일반). */
    public static Vehicle van(String source, boolean active) {
        return new Vehicle(UUID.nameUUIDFromBytes(("van-" + source + active).getBytes()), CAMP, "V-0002", "VAN",
                400_000, 1_200_000, false, false, 45_000, 600, 250, LocalTime.of(23, 0), LocalTime.of(8, 0),
                active, source);
    }

    public static Wave wave(Instant cutoff, String status, @Nullable Integer routes, @Nullable Instant closedAt) {
        return new Wave(WAVE, "DAWN", cutoff, status, 4_500, status.equals("PLANNED") ? 9 : null, routes, closedAt);
    }

    public static Line line(String label, String status, @Nullable Integer shortfall, @Nullable Vehicle template) {
        return new Line(label, status, 4, shortfall, template);
    }

    public static Assessment assessment(Line... lines) {
        return new Assessment(WAVE, CAMP, Instant.parse("2026-09-27T14:58:00Z"), 4_480, 3_900, 8, 120, false,
                List.of(lines));
    }
}
