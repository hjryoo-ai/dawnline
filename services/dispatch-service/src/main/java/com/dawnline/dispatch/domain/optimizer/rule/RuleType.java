package com.dawnline.dispatch.domain.optimizer.rule;

/**
 * §6.3 룰 카탈로그의 타입 10종.
 *
 * <p>enum 인 이유는 <strong>평가기가 코드</strong>이기 때문이다 — 정의는 데이터로 늘릴 수 있지만
 * 새 타입은 구현이 필요하고, 구현 없는 타입이 설정에 들어오면 계획 시점이 아니라 <em>로딩 시점에</em>
 * 실패해야 한다.
 */
public enum RuleType {

    /** 냉장 주문 → 냉장 차량, 위험물 → 허용 차량. */
    VEHICLE_ATTRIBUTE_MATCH(RuleSeverity.HARD),
    /** 중량·부피 누적 ≤ 용량. */
    VEHICLE_CAPACITY(RuleSeverity.HARD),
    /** 라우트당 최대 stop 수. */
    MAX_STOPS_PER_ROUTE(RuleSeverity.HARD),
    /** 복귀 시각 ≤ 근무 종료 − 버퍼. */
    SHIFT_WINDOW(RuleSeverity.HARD),
    /** 약속창 초과가 N분 이상이면 배정 불가. */
    TIME_WINDOW_LIMIT(RuleSeverity.HARD),

    /** 약속창 초과 분당 페널티. */
    TIME_WINDOW_PENALTY(RuleSeverity.SOFT),
    /** 라우트가 여러 권역에 걸치면 페널티. */
    ZONE_AFFINITY(RuleSeverity.SOFT),
    /** 우선 고객을 앞 순서에 두면 보너스. */
    PRIORITY_BOOST(RuleSeverity.SOFT),
    /** 소형 물량에 비선호 차종을 쓰면 페널티. */
    VEHICLE_PREFERENCE(RuleSeverity.SOFT),
    /** 미배정 비용. */
    UNASSIGNED_PENALTY(RuleSeverity.SOFT);

    private final RuleSeverity severity;

    RuleType(RuleSeverity severity) {
        this.severity = severity;
    }

    /** 이 타입의 심각도. 정의 파일이 적은 값과 같아야 한다. */
    public RuleSeverity severity() {
        return severity;
    }
}
