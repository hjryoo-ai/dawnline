package com.dawnline.common.archunit.samples.good.adapter.in.web;

import com.dawnline.common.archunit.samples.good.application.SampleUseCase;
import com.dawnline.common.archunit.samples.good.domain.SampleOrder;

/** 규칙 검증용 표본: 허용된 방향(adapter → application)으로만 의존하는 인바운드 어댑터. */
public final class SampleController {

    private final SampleUseCase useCase = new SampleUseCase();

    public SampleOrder handle(SampleOrder order, long krw) {
        return useCase.applySurcharge(order, krw);
    }
}
