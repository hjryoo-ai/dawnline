package com.dawnline.dispatch.application.port.in;

import java.util.UUID;

/**
 * 웨이브 하나를 계획한다 (DESIGN.md §5.3).
 *
 * <p>{@code wave.closed} 소비와 운영자의 수동 재실행이 같은 입구를 쓴다 — 두 경로가 다른 코드를
 * 지나면 "운영자가 돌리면 되는데 자동은 안 된다" 같은 차이가 생긴다.
 */
public interface RunPlanUseCase {

    /**
     * @param command 실행 명령
     * @return 처리 결과
     */
    Outcome run(RunPlanCommand command);

    /** 처리 결과. */
    enum Outcome {
        /** 계획하고 발행했다. */
        PUBLISHED,
        /** 계획했으나 배정된 주문이 하나도 없어 실패로 종결했다. */
        FAILED,
        /** 이미 발행된 웨이브다. 아무것도 하지 않았다 (§5.3 멱등). */
        ALREADY_PUBLISHED,
        /** 계획할 후보가 없다. */
        NO_CANDIDATES
    }
}
