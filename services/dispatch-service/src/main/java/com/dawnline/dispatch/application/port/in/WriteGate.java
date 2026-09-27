package com.dawnline.dispatch.application.port.in;

/**
 * 쓰기를 감싸는 자리. 쓰기를 실행했으면 참, 건너뛰었으면 거짓을 돌려준다 (ADR-064 결정 2 · ADR-068 결정 1).
 *
 * <p>계획과 재계획은 읽기 → 계산 → 쓰기 셋이고 계산은 트랜잭션 밖에서 돈다. 부르는 쪽이 「이 쓰기를 해도 되는가」를 쓰기
 * 트랜잭션 안에서 먼저 묻고 싶으면 게이트를 넘긴다 — 리스너의 멱등 게이트({@code IdempotentConsumer.runOnce})가 그것이다(불변규칙 2).
 * 유스케이스는 게이트가 무엇을 묻는지 모른다.
 *
 * <p>게이트가 트랜잭션을 열면 쓰기는 그 트랜잭션에 합류한다 — 게이트의 검사와 쓰기가 한 번에 커밋되거나 함께 롤백된다.
 * 게이트가 돌아온 뒤에는 커밋이 끝났다. 유스케이스와 리스너는 그 뒤에 카운터를 올린다(ADR-064 결정 5).
 */
@FunctionalInterface
public interface WriteGate {

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
