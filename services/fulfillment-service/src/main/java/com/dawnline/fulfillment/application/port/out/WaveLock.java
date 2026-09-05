package com.dawnline.fulfillment.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * 웨이브 마감의 분산 락 (§5.2, §7.2 {@code lock:wave:{id}}).
 *
 * <h2>이 락은 정확성의 근거가 아니다</h2>
 * 중복 마감을 <strong>실제로</strong> 막는 것은 세 겹 중 뒤의 둘이다.
 *
 * <ol>
 *   <li>이 락 — 두 인스턴스가 <em>같은 일을 동시에 시작하지</em> 않게 한다. 낭비를 줄이는 방어다.</li>
 *   <li>{@code SELECT … FOR UPDATE} — 진행 중인 편입이 끝날 때까지 기다린 뒤 배타로 잡는다.</li>
 *   <li>상태 전이 자체 — {@code OPEN} 이 아니면 마감하지 않는다. 두 번째 인스턴스는 여기서 멈춘다.</li>
 * </ol>
 *
 * <p>그래서 <strong>Redis 가 없어도 정확성이 유지된다</strong>(불변규칙 7). 락을 얻지 못하는
 * 상황과 Redis 가 죽은 상황을 구별해야 하는 이유가 여기 있다 — 전자는 스킵이지만 후자는
 * <em>진행</em>이다. Redis 장애로 마감이 멈추면 그것은 폴백이 아니라 서비스 중단이다.
 */
public interface WaveLock {

    /**
     * 락을 시도한다.
     *
     * @param waveId 웨이브 id
     * @return 얻었으면 해제용 핸들. 다른 인스턴스가 쥐고 있으면 비어 있다.
     *         <strong>Redis 장애일 때는 얻은 것으로 본다</strong>(fail-open) — 그 판단의 근거는
     *         위 세 겹이고, 건너뛴 사실은 메트릭으로 남는다
     */
    Optional<Guard> tryLock(UUID waveId);

    /** 락 해제 핸들. {@code try-with-resources} 로 쓴다. */
    interface Guard extends AutoCloseable {

        /** 락을 놓는다. 실패해도 예외를 내지 않는다 — TTL 이 결국 정리한다. */
        @Override
        void close();
    }
}
