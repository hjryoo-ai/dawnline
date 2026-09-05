package com.dawnline.dispatch.domain.optimizer;

/**
 * 디스패치 룰 (DESIGN.md §6.3).
 *
 * <h2>룰은 데이터, 평가기는 코드</h2>
 * 룰 <em>정의</em>는 {@code dispatch_rules} 테이블에 있고 타입별 <em>평가기</em>만 코드로 제공한다.
 * 그래서 새 룰을 켜는 데 배포가 필요 없고, 룰을 바꾼 사실이 {@code rule_version} 으로 남는다.
 *
 * <p>{@code sealed} 인 이유는 하드와 소프트의 처리가 <strong>완전히 다르기</strong> 때문이다 —
 * 하드는 첫 위반에서 중단하고 소프트는 전부 합산한다. 제3의 심각도가 생기면 그 분기를 처리하지
 * 않은 자리가 컴파일 에러로 드러나야 한다.
 */
public sealed interface DispatchRule permits HardRule, SoftRule {

    /** 룰 이름. {@code dispatch_rules.name} 이고 {@link Explanation} 에 그대로 실린다. */
    String name();

    /**
     * 평가 우선순위. 작을수록 먼저다.
     *
     * <p>하드 룰에서는 <strong>어떤 사유가 기록되는지</strong>를 정한다 — 냉장 차량이 없어서 못
     * 싣는 것과 용량이 모자라서 못 싣는 것 중 운영자에게 유용한 쪽이 먼저 와야 한다.
     */
    int priority();
}
