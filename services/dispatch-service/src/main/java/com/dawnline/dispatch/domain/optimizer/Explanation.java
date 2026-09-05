package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.error.ValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * "왜 이렇게 됐는가" 한 줄 (DESIGN.md §6.3, {@code plan_explanations} 한 행).
 *
 * <p>§6.3 이 룰을 데이터로 둔 이유가 여기 있다 — 운영자가 "왜 이 주문이 미배정인지 / 왜 이
 * 차량인지" 를 볼 수 없으면 룰을 바꿀 근거도 없다.
 *
 * <p>차량을 {@code routeId} 가 아니라 {@link VehicleId} 로 들고 있는 이유: 라우트 id 는 저장할 때
 * 생긴다. 순수 함수는 아직 없는 식별자를 만들어 내지 않는다 — 매핑은 어댑터가 한다.
 *
 * @param orderId  대상 주문
 * @param outcome  결과
 * @param ruleName 판정에 관여한 룰 이름. 없을 수 있다
 * @param vehicle  배정된 차량. 미배정이면 {@code null}
 * @param detail   추가 정보. 값은 문자열·숫자·불리언만 (JSONB 로 그대로 들어간다)
 */
public record Explanation(OrderId orderId, Outcome outcome, String ruleName, VehicleId vehicle,
        Map<String, Object> detail) {

    /** 판정 결과. */
    public enum Outcome {
        /** 라우트에 배정됐다. */
        ASSIGNED,
        /** 배정되지 못했다. */
        UNASSIGNED
    }

    public Explanation {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(outcome, "outcome");
        detail = Map.copyOf(Objects.requireNonNull(detail, "detail"));
        detail.forEach((key, value) -> {
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw ValidationException.field("detail." + key, value,
                        "설명 값은 문자열·숫자·불리언만 담을 수 있습니다");
            }
        });
        if (outcome == Outcome.ASSIGNED && vehicle == null) {
            throw ValidationException.field("vehicle", null, "배정된 설명에는 차량이 있어야 합니다");
        }
    }

    /**
     * 배정 설명.
     *
     * @param orderId      주문
     * @param vehicle      배정된 차량
     * @param marginalCost 이 배치의 한계비용
     */
    public static Explanation assigned(OrderId orderId, VehicleId vehicle, long marginalCost) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("marginalCostKrw", marginalCost);
        return new Explanation(orderId, Outcome.ASSIGNED, null, vehicle, detail);
    }

    /**
     * 미배정 설명. 사유가 곧 {@link Feasibility} 의 위반 내용이다.
     *
     * @param orderId       주문
     * @param feasibility   불가 판정
     * @param triedVehicles 시도한 차량 수
     */
    public static Explanation unassigned(OrderId orderId, Feasibility feasibility, int triedVehicles) {
        if (feasibility.feasible()) {
            throw ValidationException.field("feasibility", feasibility, "통과 판정으로 미배정 설명을 만들 수 없습니다");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", feasibility.reason());
        detail.put("triedVehicles", triedVehicles);
        return new Explanation(orderId, Outcome.UNASSIGNED, feasibility.ruleName(), null, detail);
    }
}
