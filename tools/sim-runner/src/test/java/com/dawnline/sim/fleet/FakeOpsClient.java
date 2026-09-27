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

    public final List<NewVehicle> addedBodies = new ArrayList<>();
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

    /** 첫 등록이 만든 차량 id — 비활성화의 답을 미리 정할 때 쓴다. */
    public UUID deactivateCallsTarget() {
        return UUID.nameUUIDFromBytes(addedBodies.getFirst().code().getBytes());
    }

    private String nextAudit() {
        return "audit-" + (++audit);
    }

    // --- 픽스처 --------------------------------------------------------------------------------------------

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
