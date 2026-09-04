package com.dawnline.order.application.port.out;

import java.util.UUID;

/**
 * 고객별 레이트 리밋 (DESIGN.md §7.2 `rl:customer:{id}`, §8.3).
 *
 * <h2>왜 유스케이스가 아니라 웹 어댑터가 부르는가</h2>
 * 레이트 리밋은 <strong>API 표면을 보호하는 장치</strong>이지 주문 접수의 규칙이 아니다.
 * 유스케이스 안에 두면 리스너나 sim-runner 가 같은 유스케이스를 부를 때도 걸리고, 그것은
 * "고객이 API 를 얼마나 자주 부르는가" 와 아무 상관이 없다. 그래서 인바운드 어댑터가 이 포트를
 * 직접 쓴다 — 의존 방향은 여전히 adapter → application 이다.
 *
 * <h2>실패하면 허용한다</h2>
 * Redis 를 못 쓰면 {@link Outcome#BYPASSED} 로 통과시킨다(§7.2 폴백). 다만 그 상태는
 * <em>반드시 보여야 한다</em> — 인증이 없는 API 에서 이것은 유일한 남용 방지 수단이고(§10),
 * 조용히 꺼지면 아무도 모른다. 그래서 판정마다 메트릭을 남기고 §9.4 가 알림을 건다.
 */
@FunctionalInterface
public interface RateLimiter {

    /** 판정 결과. */
    enum Outcome {

        /** 토큰이 있었다. 처리한다. */
        ALLOWED,

        /** 토큰이 없다. 429 다. */
        LIMITED,

        /** Redis 를 못 써서 판정을 건너뛰었다. 통과시키되 알림 대상이다. */
        BYPASSED
    }

    /**
     * 판정 결과와 재시도까지 남은 시간.
     *
     * @param outcome           판정
     * @param retryAfterSeconds {@link Outcome#LIMITED} 일 때 다음 토큰까지 남은 초(올림).
     *                          그 밖에는 0
     */
    record Decision(Outcome outcome, int retryAfterSeconds) {

        /** 요청을 처리해도 되는가. 건너뛴 것도 처리한다. */
        public boolean isAllowed() {
            return outcome != Outcome.LIMITED;
        }
    }

    /**
     * 토큰 하나를 소비한다.
     *
     * @param customerId 고객 id. 무인증 API 이므로 클라이언트 주장값이다 (§10)
     * @return 판정
     */
    Decision tryAcquire(UUID customerId);
}
