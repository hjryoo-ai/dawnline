package com.dawnline.common.archunit.samples.good.domain;

import com.dawnline.common.Money;

/**
 * 규칙 검증용 표본: 프레임워크에 의존하지 않는 도메인 값 객체.
 *
 * <p>{@code HexagonalArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE} 가 이 패키지를 통과해야 한다.
 */
public record SampleOrder(String id, Money total) {

    public SampleOrder withSurcharge(Money surcharge) {
        return new SampleOrder(id, total.plus(surcharge));
    }
}
