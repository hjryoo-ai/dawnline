package com.dawnline.sim.driver;

/**
 * tracking 이 {@code route.assigned} 를 어디까지 읽었나 — 그 컨슈머 그룹의 랙 (ADR-067 후속 「기사는 반영 뒤에 출발한다」).
 *
 * <p>{@link RouteFeed} 와 같은 이유로 포트로 둔다 — {@link DepartureGate} 를 브로커 없이 시험한다. 운영 구현은
 * {@link KafkaGroupLag} 하나다.
 */
@FunctionalInterface
public interface ApplyLag {

    /**
     * 그 그룹이 아직 커밋하지 않은 레코드 수 — 토픽의 파티션 합.
     *
     * @return 0 이면 지금 로그의 끝까지 반영했다
     * @throws IllegalStateException 브로커에 묻지 못했으면. 모르는 것을 0 으로 접지 않는다
     */
    long remaining();
}
