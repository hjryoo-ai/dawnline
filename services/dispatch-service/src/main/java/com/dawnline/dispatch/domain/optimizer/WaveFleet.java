package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.TimeWindow;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 이 웨이브가 <strong>쓸 수 있는</strong> 차량 (DESIGN.md §6.2, [ADR-039] 후속).
 *
 * <h2>왜 집합을 거르는가 — 룰이 stop 마다 거절하는데도</h2>
 * 약속창이 끝난 뒤에야 근무를 시작하는 차량은 근무창 · 약속창 룰이 stop 마다 거절하므로 <em>배정</em>에는 해가 없다. 그런데 차량 목록을
 * 그대로 쓰는 자리가 배정 하나가 아니다 — 좌석 예약이 라운드로빈을 도는 목록이고, 미배정 설명이 세는 목록이다. 첫 {@code peak-day}
 * 에서 주간 냉장 차량이 새벽 웨이브의 냉장 좌석을 나눠 받았고, 아무도 앉을 수 없는 그 자리만큼 냉장 주문이 미배정으로 남았다
 * (그림자 재실행: 2,337 → 593).
 *
 * <h2>술어 하나, 자리 하나</h2>
 * 함대 판정({@code AssessFleetService})과 계획({@code RunPlanService.problemOf})이 <strong>이 함수 하나</strong>를 부른다. 둘이 따로
 * 적으면 「기준이 센 차량」과 「계획이 쓴 차량」이 갈라지고, 그 틈이 증차의 대수를 틀리게 한다.
 *
 * <h2>술어는 하드 룰보다 엄격하지 않다</h2>
 * 여기서는 <strong>한 stop 도 실을 수 없는 차</strong>만 뺀다 — 어느 stop 을 실을지는 여전히 stop 마다의 룰이다. 룰은 지각만 막고 이른
 * 도착은 막지 않으므로({@link PlannedStop#lateMinutes()}, 모델에 기다림이 없다) 근무 <em>끝</em>은 술어에 들지 않는다: 약속창보다 먼저
 * 끝나는 근무도 이르게 배송할 수 있다. 처음 판의 「근무창과 약속창이 겹친다」는 그 차를 뺐고, 내일 약속창의 웨이브를 오늘 조기 마감한
 * 데모에서 쓸 차량이 0대가 됐다(Compose 스모크, 2026-09-27). 근무 끝이 계획 시각 뒤라는 것은 읽기 단계가 보장한다.
 */
public final class WaveFleet {

    private WaveFleet() {
    }

    /**
     * 근무가 후보 약속창의 합이 끝나기 전에 시작하는 차량. 순서는 그대로다 — 예약의 라운드로빈과 동률의 마지막 키가 그 순서를 쓴다.
     *
     * @param fleet      캠프의 가용 차량
     * @param candidates 그 웨이브의 계획 대상 후보. 비면 쓸 차량도 없다
     */
    public static List<VehicleSpec> usable(List<VehicleSpec> fleet, List<Candidate> candidates) {
        Objects.requireNonNull(fleet, "fleet");
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            return List.of();
        }
        TimeWindow promised = promisedSpan(candidates);
        return fleet.stream().filter(vehicle -> vehicle.shift().start().isBefore(promised.end())).toList();
    }

    /** 후보 약속창의 합 — 가장 이른 시작부터 가장 늦은 끝까지. */
    static TimeWindow promisedSpan(List<Candidate> candidates) {
        Instant start = candidates.stream().map(c -> c.promised().start()).min(Comparator.naturalOrder()).orElseThrow();
        Instant end = candidates.stream().map(c -> c.promised().end()).max(Comparator.naturalOrder()).orElseThrow();
        return new TimeWindow(start, end);
    }
}
