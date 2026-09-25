package com.dawnline.common.archunit.samples.good.application;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 규칙 11 의 반대 방향 표본 — 레지스트리에서 미터를 <strong>읽는</strong> 것은 등록이 아니다([ADR-060] 결정 2).
 */
public class MeterReadingUseCase {

    private final MeterRegistry registry;

    /**
     * @param registry 레지스트리
     */
    public MeterReadingUseCase(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * @return 그런 카운터가 있는가
     */
    public boolean exists() {
        return registry.find("dawnline.orders.placed").counter() != null && !registry.getMeters().isEmpty();
    }
}
