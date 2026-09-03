package com.dawnline.order.application.port.out;

/**
 * 멱등 키의 in-flight 표시 (DESIGN.md §5.1 · §7.2 {@code idem:order:{key}}, ADR-018).
 *
 * <p><strong>진실 저장소가 아니다</strong>(불변규칙 7). 이 캐시가 통째로 사라져도 정확성은
 * {@link IdempotencyRecords} 의 기본 키가 지킨다. 여기서 얻는 것은 "같은 요청을 두 번 처리하고
 * 하나를 롤백하는" 헛일을 줄이는 것뿐이다.
 *
 * <p>그래서 모든 메서드는 <strong>예외를 던지지 않는다</strong>. Redis 장애가 주문 접수를 막으면
 * 규칙 7 을 어기는 것이다.
 */
public interface IdempotencyCache {

    /** {@link #tryLock(String)} 의 결과. */
    enum Lock {

        /** 이 요청이 잠금을 얻었다. 처리해도 된다. */
        ACQUIRED,

        /** 다른 요청이 이미 그 키를 쥐고 있다(또는 최근에 끝냈다). */
        HELD,

        /** Redis 에 물어보지 못했다. 잠금 없이 진행한다 — 정확성은 DB 가 지킨다. */
        UNAVAILABLE
    }

    /**
     * {@code SET idem:order:{key} IN_PROGRESS NX PX 30000} (§5.1 2단계).
     *
     * <p>만료를 두는 것이 이 설계의 핵심이다 — 프로세스가 죽어도 30초 뒤 잠금이 스스로 풀린다.
     *
     * @param key 멱등 키
     * @return 획득 여부 또는 {@link Lock#UNAVAILABLE}
     */
    Lock tryLock(String key);

    /**
     * 처리가 끝났음을 표시한다 ({@code DONE}, TTL 24h). 실패해도 조용히 넘어간다 —
     * 다음 요청은 DB 에서 같은 답을 얻는다.
     *
     * @param key 멱등 키
     */
    void markDone(String key);

    /**
     * 잠금을 푼다. 처리에 실패했을 때 부른다 — 지우지 않으면 30초 동안 재시도가 409 가 된다.
     *
     * @param key 멱등 키
     */
    void release(String key);
}
