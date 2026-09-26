package com.dawnline.dispatch.application.port.in;

/**
 * 웨이브 하나를 계획한다 (DESIGN.md §5.3).
 *
 * <p>{@code wave.closed} 소비와 운영자의 수동 재실행이 같은 입구를 쓴다 — 두 경로가 다른 코드를
 * 지나면 "운영자가 돌리면 되는데 자동은 안 된다" 같은 차이가 생긴다.
 *
 * <h2>게이트는 쓰기만 감싼다 (ADR-064)</h2>
 * 계획은 읽기 → 계산 → 쓰기 셋이고, 계산은 트랜잭션 밖에서 돈다. 부르는 쪽이 「이 쓰기를 해도 되는가」를 쓰기 트랜잭션 안에서
 * 먼저 묻고 싶으면 {@link WriteGate} 를 넘긴다 — {@code wave.closed} 리스너의 멱등 게이트가 그것이다(불변규칙 2). 유스케이스는
 * 게이트가 무엇을 묻는지 모른다.
 */
public interface RunPlanUseCase {

    /**
     * 게이트 없이 — 운영자 재실행(웹)의 형태.
     *
     * @param command 실행 명령
     * @return 처리 결과
     */
    default Outcome run(RunPlanCommand command) {
        return run(command, WriteGate.OPEN);
    }

    /**
     * @param command 실행 명령
     * @param gate    쓰기를 감싸는 게이트. 계산 뒤에 한 번 불린다
     * @return 처리 결과
     */
    Outcome run(RunPlanCommand command, WriteGate gate);

    /**
     * 쓰기를 감싸는 자리. 쓰기를 실행했으면 참, 건너뛰었으면 거짓을 돌려준다.
     *
     * <p>게이트가 트랜잭션을 열면 쓰기는 그 트랜잭션에 합류한다 — 게이트의 검사와 쓰기가 한 번에 커밋되거나 함께 롤백된다.
     * 게이트가 돌아온 뒤에는 커밋이 끝났다. 유스케이스는 그 뒤에 카운터를 올린다(ADR-064 결정 5).
     */
    @FunctionalInterface
    interface WriteGate {

        /** 묻지 않는다 — 쓰기는 자기 트랜잭션을 연다. */
        WriteGate OPEN = write -> {
            write.run();
            return true;
        };

        /**
         * @param write 쓰기
         * @return 쓰기를 실행했으면 {@code true}
         */
        boolean enter(Runnable write);
    }

    /** 처리 결과. */
    enum Outcome {
        /** 계획하고 발행했다. */
        PUBLISHED,
        /** 계획했으나 배정된 주문이 하나도 없어 실패로 종결했다. */
        FAILED,
        /** 이미 발행된 웨이브다. 아무것도 하지 않았다 (§5.3 멱등). */
        ALREADY_PUBLISHED,
        /** 계획할 후보가 없다. */
        NO_CANDIDATES,
        /** 게이트가 쓰기를 건너뛰었다 — 같은 이벤트를 이미 처리했다. 계산한 결과는 버렸다 (ADR-064 결정 2). */
        DUPLICATE
    }
}
