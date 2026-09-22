package com.dawnline.sim.driver;

import java.util.UUID;

/**
 * 지연·실패 주입 — <strong>전부 seed 에서 뽑는다</strong> (불변규칙 12,
 * IMPLEMENTATION_PLAN Phase 5-2 「지연·실패 주입도 전부 seed 에서 뽑는다」).
 *
 * <h2>왜 난수원을 들고 순서대로 뽑지 않는가</h2>
 * {@link DriverSimulator} 는 개정이 올 때마다 <em>다시</em> 불린다. 난수원을 들고 순서대로
 * 뽑으면 같은 구간의 지연이 「몇 번째 호출이었나」에 따라 달라지고, 그러면 같은 seed 가 같은
 * 시나리오를 내지 않는다. 그래서 이 인터페이스는 <strong>좌표로 조회한다</strong> —
 * {@code (routeId, revision, seq)} 가 주어지면 언제 몇 번을 물어도 같은 값이 나온다.
 * 순수 함수가 순수하려면 그 입력도 순수해야 한다.
 */
public interface Jitter {

    /** 아무것도 주입하지 않는다 — 계획대로 도는 기사. */
    Jitter NONE = new Jitter() {

        @Override
        public double delayFactor(UUID routeId, int revision, int seq) {
            return 0.0;
        }

        @Override
        public boolean fails(UUID routeId, int revision, int seq) {
            return false;
        }

        @Override
        public long departureDelaySeconds(UUID routeId, int revision) {
            return 0L;
        }

        @Override
        public String failureReason(UUID routeId, int revision, int seq) {
            throw new IllegalStateException("실패를 주입하지 않는 Jitter 에는 사유가 없다");
        }
    };

    /**
     * 이 구간의 지연 비율. 실제 이동 시간 = 계획 이동 시간 × (1 + 이 값).
     *
     * @param routeId  라우트 id
     * @param revision 개정 번호. 재계획하면 구간이 달라지므로 좌표에 들어간다
     * @param seq      도착할 stop 의 순번
     * @return 0 이상. 0 이면 계획대로다
     */
    double delayFactor(UUID routeId, int revision, int seq);

    /**
     * 이 stop 의 전달이 실패하는가.
     *
     * @param routeId  라우트 id
     * @param revision 개정 번호
     * @param seq      stop 순번
     */
    boolean fails(UUID routeId, int revision, int seq);

    /**
     * 캠프 출발 지연(초).
     *
     * <p>따로 두는 이유는 §5.4 다 — 늦은 출발은 가장 흔한 지연 원인이고 첫 도착 스캔 <em>전에</em>
     * 이미 알 수 있는 위험이다. 구간 지연과 같은 축에 두면 「늦게 출발했다」만 주입할 수 없다.
     *
     * @param routeId  라우트 id
     * @param revision 개정 번호
     */
    long departureDelaySeconds(UUID routeId, int revision);

    /**
     * 실패 사유. {@link #fails(UUID, int, int)} 가 참일 때만 부른다.
     *
     * @param routeId  라우트 id
     * @param revision 개정 번호
     * @param seq      stop 순번
     */
    String failureReason(UUID routeId, int revision, int seq);
}
