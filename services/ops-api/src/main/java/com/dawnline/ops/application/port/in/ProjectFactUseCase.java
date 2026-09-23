package com.dawnline.ops.application.port.in;

/**
 * 사실 하나를 읽기 모델에 반영한다 (DESIGN.md §5.5, ADR-051).
 *
 * <p>호출자의 트랜잭션 안에서 돈다 — 리스너가 {@code IdempotentConsumer} 로 감싸
 * {@code processed_events} 기록과 같은 트랜잭션이 된다(불변규칙 2).
 */
public interface ProjectFactUseCase {

    /**
     * @param fact 받은 사실
     * @return 반영 결과
     */
    Projection project(Fact fact);

    /**
     * 반영 결과.
     *
     * @param stale 이미 지나온 자리라 적지 않은 판정의 수 — {@code dawnline_event_stale_total} 에
     *              더한다. 행이 여럿인 사실(라우트의 주문들)은 행마다 센다
     */
    record Projection(int stale) {

        /** 아무것도 역행하지 않았다. */
        public static final Projection CLEAN = new Projection(0);
    }
}
