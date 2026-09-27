package com.dawnline.dispatch.application.port.in;

import java.util.UUID;

/** 웨이브의 함대 실현 가능성 (DESIGN.md §5.3 「함대」, ADR-067 결정 2). 읽기다. */
public interface AssessFleetUseCase {

    /**
     * 그 웨이브의 계획 대상 후보(통합 후 stop)와 계획이 쓸 수 있는 차량을 맞댄다.
     *
     * <p>발행된 계획이 있으면 409 {@code wave-already-planned}, 계획 대상 후보가 없으면 404.
     *
     * @param waveId 웨이브
     * @return 조합별 판정
     */
    ResourceViews.FleetFeasibilityView assess(UUID waveId);
}
