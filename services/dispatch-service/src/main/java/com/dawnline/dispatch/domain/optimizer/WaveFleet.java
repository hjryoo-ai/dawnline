package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.TimeWindow;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 이 웨이브가 <strong>쓸 수 있는</strong> 차량 (DESIGN.md §6.2, [ADR-039] 후속 · [ADR-075] 결정 3 · 5).
 *
 * <h2>왜 집합을 거르는가 — 룰이 stop 마다 거절하는데도</h2>
 * 약속창과 겹치지 않는 근무의 차량은 근무창 · 약속창 룰이 stop 마다 거절하므로 <em>배정</em>에는 해가 없다. 그런데 차량 목록을
 * 그대로 쓰는 자리가 배정 하나가 아니다 — 좌석 예약이 라운드로빈을 도는 목록이고, 미배정 설명이 세는 목록이다. 첫 {@code peak-day}
 * 에서 주간 냉장 차량이 새벽 웨이브의 냉장 좌석을 나눠 받았고, 아무도 앉을 수 없는 그 자리만큼 냉장 주문이 미배정으로 남았다
 * (그림자 재실행: 2,337 → 593).
 *
 * <h2>술어 하나, 자리 하나</h2>
 * 함대 판정({@code AssessFleetService})과 계획({@code RunPlanService.problemOf})이 <strong>이 함수 하나</strong>를 부른다. 둘이 따로
 * 적으면 「기준이 센 차량」과 「계획이 쓴 차량」이 갈라지고, 그 틈이 증차의 대수를 틀리게 한다.
 *
 * <h2>술어는 겹침이다 (2026-09-28, ADR-075 결정 5)</h2>
 * {@code available_from < 약속창 끝 ∧ 근무 끝 > 약속창 시작}. 차량의 근무 시작은 이미 {@code available_from} 이다({@link #availableFrom}).
 * 한때 근무 끝을 술어에서 뺐었다 — 룰이 이른 도착을 막지 않았고(모델에 기다림이 없었다), 근무를 <em>계획 시각</em> 기준으로 붙여
 * 내일 약속창의 웨이브를 오늘 조기 마감한 데모가 쓸 차량 0대가 됐다. 이제 약속창 시작은 하한이고(§2.2) 근무는 약속창에 닿는 근무라
 * 두 이유가 모두 없다.
 */
public final class WaveFleet {

    private WaveFleet() {
    }

    /**
     * 근무가 후보 약속창의 합과 겹치는 차량. 순서는 그대로다 — 예약의 라운드로빈과 동률의 마지막 키가 그 순서를 쓴다.
     *
     * @param fleet      캠프의 가용 차량 — 근무 시작은 {@code available_from}
     * @param candidates 그 웨이브의 계획 대상 후보. 비면 쓸 차량도 없다
     */
    public static List<VehicleSpec> usable(List<VehicleSpec> fleet, List<Candidate> candidates) {
        Objects.requireNonNull(fleet, "fleet");
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            return List.of();
        }
        TimeWindow promised = promisedSpan(candidates);
        return fleet.stream().filter(vehicle -> vehicle.shift().start().isBefore(promised.end())
                && vehicle.shift().end().isAfter(promised.start())).toList();
    }

    /**
     * 근무 시작을 {@code available_from = max(근무 시작, busyUntil)} 로 민다(ADR-075 결정 3). 앞선 계획의 라우트가 근무 끝 이후에야
     * 돌아오면 이 차는 없다.
     *
     * @param vehicle   근무가 약속창에 닿는 차량
     * @param busyUntil 그 차량의 끝나지 않은 발행 라우트 중 가장 늦은 계획 복귀. 없으면 {@code null}
     */
    public static Optional<VehicleSpec> availableFrom(VehicleSpec vehicle, @Nullable Instant busyUntil) {
        Objects.requireNonNull(vehicle, "vehicle");
        if (busyUntil == null || !busyUntil.isAfter(vehicle.shift().start())) {
            return Optional.of(vehicle);
        }
        if (!busyUntil.isBefore(vehicle.shift().end())) {
            return Optional.empty();
        }
        return Optional.of(new VehicleSpec(vehicle.id(), vehicle.capacity(), vehicle.attrs(),
                new TimeWindow(busyUntil, vehicle.shift().end()), vehicle.cost()));
    }

    /** 후보 약속창의 합 — 가장 이른 시작부터 가장 늦은 끝까지. */
    public static TimeWindow promisedSpan(List<Candidate> candidates) {
        Instant start = candidates.stream().map(c -> c.promised().start()).min(Comparator.naturalOrder()).orElseThrow();
        Instant end = candidates.stream().map(c -> c.promised().end()).max(Comparator.naturalOrder()).orElseThrow();
        return new TimeWindow(start, end);
    }

    /** stop 약속창의 합 — 계획 뒤에 라우트의 시각을 다시 계산하는 경로가 근무를 붙일 때 쓴다. */
    public static TimeWindow promisedSpanOfStops(List<Stop> stops) {
        Instant start = stops.stream().map(s -> s.promised().start()).min(Comparator.naturalOrder()).orElseThrow();
        Instant end = stops.stream().map(s -> s.promised().end()).max(Comparator.naturalOrder()).orElseThrow();
        return new TimeWindow(start, end);
    }
}
