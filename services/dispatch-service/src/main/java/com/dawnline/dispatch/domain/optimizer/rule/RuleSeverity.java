package com.dawnline.dispatch.domain.optimizer.rule;

/**
 * 룰 심각도 (DESIGN.md §6.3, {@code dispatch_rules.severity}).
 *
 * <p>타입이 심각도를 이미 정하므로({@link RuleType}) 이 값은 <strong>정의 파일이 자기 자신과
 * 어긋나지 않았는지</strong>를 검사하는 데 쓴다. 검사하지 않으면 JSON 이 거짓말을 해도 조용히
 * 지나가고, 나중에 "HARD 로 적었는데 왜 배정됐지" 를 코드에서 찾게 된다.
 */
public enum RuleSeverity {
    /** 위반하면 배정 불가. */
    HARD,
    /** 비용에 가산. */
    SOFT
}
