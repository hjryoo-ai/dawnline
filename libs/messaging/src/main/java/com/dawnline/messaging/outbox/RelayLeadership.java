package com.dawnline.messaging.outbox;

/**
 * 릴레이의 단일 활성 인스턴스를 정한다 (DESIGN.md §4.4, [ADR-027]).
 *
 * <h2>무엇을 지키는가</h2>
 * {@code FOR UPDATE SKIP LOCKED} 는 두 인스턴스가 <em>같은 행</em>을 발행하는 것을 막는다. 막지
 * 못하는 것은 <strong>키 단위 순서</strong>다 — 같은 {@code partition_key} 의 두 행이 서로 다른
 * 인스턴스에서 나가면 배치 시점과 전송 시점이 달라 §4.5 의 보장이 깨진다. 그 순서를 지키는 유일한
 * 방법은 어느 한 순간에 발행하는 인스턴스가 하나인 것이고, 그것을 정하는 것이 이 포트다.
 *
 * <h2>세 번째 상태를 값으로 둔다</h2>
 * "내가 리더다" 와 "남이 리더다" 만으로는 <strong>모른다</strong>를 표현할 수 없다. Redis 가 죽으면
 * 판정 자체가 불가능한데, 그것을 {@code false} 로 접으면 운영자는 대시보드에서 "다른 인스턴스가
 * 리더" 와 "아무도 판정하지 못하는 중" 을 구별할 수 없다. 발행을 멈추는 <em>결정</em>은 둘이 같지만
 * <em>봐야 할 곳</em>이 전혀 다르다 — 전자는 정상, 후자는 Redis 장애다.
 */
public interface RelayLeadership {

    /**
     * 리더십을 얻거나 갱신한다. 릴레이가 배치를 발행하기 <strong>전에</strong> 매번 부른다.
     *
     * <p>획득과 갱신이 한 메서드인 이유: 둘은 같은 질문의 두 경우이고, 나누면 호출부가 "지금 내가
     * 리더인가" 를 상태로 들고 있어야 한다. 그 상태는 TTL 만료와 어긋날 수 있다.
     */
    State lead();

    /**
     * 리더십을 내려놓는다 (정상 종료). TTL 을 기다리지 않고 다음 인스턴스가 바로 이어받게 한다.
     *
     * <p>실패해도 예외를 던지지 않는다 — TTL 이 결국 정리하고, 종료 경로에서 던진 예외는
     * 종료를 실패로 보이게 만든다.
     */
    void stepDown();

    /**
     * 릴레이 리더십 상태.
     */
    enum State {

        /** 이 인스턴스가 리더다. 발행한다. */
        LEADER,

        /** 다른 인스턴스가 리더다. 발행하지 않는다. <strong>정상</strong>이다. */
        FOLLOWER,

        /** 판정할 수 없다(조정자 장애). 발행하지 않는다. <strong>장애</strong>다. */
        UNKNOWN
    }

    /**
     * 항상 리더인 구현 — <strong>"이 배포는 인스턴스가 하나다" 라는 선언</strong>이다.
     *
     * <p>{@code dawnline.messaging.outbox.leader.enabled=false} 일 때만 쓴다. 기본값이 아닌 이유는,
     * 이 전제가 조용히 성립하는 것과 <em>적혀 있는 것</em>이 다르기 때문이다. 스케일아웃은 설정
     * 파일을 읽지 않는다 — 인스턴스를 2개로 올리는 사람이 이 줄을 보고 지워야 한다.
     */
    static RelayLeadership singleInstance() {
        return new RelayLeadership() {

            @Override
            public State lead() {
                return State.LEADER;
            }

            @Override
            public void stepDown() {
                // 내려놓을 것이 없다.
            }

            @Override
            public String toString() {
                return "RelayLeadership(single-instance)";
            }
        };
    }
}
