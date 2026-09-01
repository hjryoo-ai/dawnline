package com.dawnline.common.archunit.samples.good.application;

import com.dawnline.common.Money;
import com.dawnline.common.archunit.samples.good.domain.SampleOrder;

/**
 * 규칙 검증용 표본: domain 만 참조하는 유스케이스.
 *
 * <p>{@code APPLICATION_DOES_NOT_DEPEND_ON_ADAPTER} 가 이 패키지를 통과해야 한다.
 */
public final class SampleUseCase {

    public SampleOrder applySurcharge(SampleOrder order, long krw) {
        return order.withSurcharge(Money.krw(krw));
    }
}
