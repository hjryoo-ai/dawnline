package com.dawnline.sim.driver;

/**
 * {@code route.assigned} 수신을 켜고 끈다.
 *
 * <p>{@link DriverScenario} 를 Kafka 없이 시험하려고 포트로 둔다. 운영 구현은 Spring 의
 * 리스너 컨테이너를 켜는 것뿐이다({@code SimRunnerConfig}).
 */
public interface RouteFeed extends AutoCloseable {

    /** 아무것도 받지 않는 피드. 기사를 쓰지 않는 시나리오가 쓴다. */
    RouteFeed NONE = new RouteFeed() {

        @Override
        public void open() {
            // 받을 것이 없다.
        }

        @Override
        public void close() {
            // 닫을 것이 없다.
        }
    };

    /** 수신을 시작한다. <strong>주문을 넣기 전에</strong> 불러야 한다 — 뒤면 라우트를 놓친다. */
    void open();

    @Override
    void close();
}
