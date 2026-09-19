package com.dawnline.tracking.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code route.assigned} 를 배송에 반영한다 (DESIGN.md §5.4, §8.5).
 *
 * <p>명령이 이벤트 페이로드가 아니라 <em>이 인터페이스의 레코드</em>인 이유는 불변규칙 5 다.
 * 어댑터가 계약의 모양(문자열 시각·{@code status} 열거·{@code cancelledOrderIds} 배열)을
 * 여기서 쓰는 값으로 옮기고, 유스케이스는 Kafka 도 JSON 도 모른다.
 */
public interface ApplyRouteAssignmentUseCase {

    /**
     * 개정 하나를 반영한다.
     *
     * @param assignment 라우트 확정/개정
     * @return 반영 결과
     */
    Outcome apply(RouteAssignment assignment);

    /**
     * 라우트 확정/개정 하나.
     *
     * @param routeId  라우트 id
     * @param revision 개정 번호. 최초 확정이 1 (§6.8 4단계)
     * @param stops    방문 순서대로의 stop 들. 취소된 stop 도 들어 있다 (ADR-026)
     */
    record RouteAssignment(UUID routeId, int revision, List<AssignedStop> stops) {

        public RouteAssignment {
            Objects.requireNonNull(routeId, "routeId");
            if (revision < 1) {
                throw new IllegalArgumentException("revision 은 1 이상이어야 합니다: " + revision);
            }
            stops = List.copyOf(stops);
            if (stops.isEmpty()) {
                throw new IllegalArgumentException("stop 이 없는 라우트는 확정될 수 없습니다: " + routeId);
            }
        }

        /** 이 개정에 실린 모든 주문 id (중복 없이). */
        public Set<UUID> allOrderIds() {
            return stops.stream().flatMap(stop -> stop.orderIds().stream())
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    /**
     * stop 하나.
     *
     * <p>{@code orderIds} 에는 취소된 주문도 남아 있고, 어느 것이 취소인지는
     * {@code cancelledOrderIds} 가 말한다 — 지우면 「취소」와 「다른 라우트로 이동」을 구별할 수
     * 없다(ADR-026 [후속 정정]). 계약의 {@code status: CANCELLED} 는 어댑터가 이 집합에 접어
     * 넣는다: 도메인에는 「이 주문이 취소됐는가」 하나만 있으면 된다.
     *
     * @param seq               방문 순번 (1부터)
     * @param orderIds          이 지점에서 배송할 주문들
     * @param cancelledOrderIds 그중 취소된 것들
     * @param plannedArrival    계획 도착 시각. {@code eta_at} 의 초기값이다
     * @param promisedEnd       약속창의 끝. at-risk 판정의 기준이다 (§5.4)
     */
    record AssignedStop(int seq, List<UUID> orderIds, Set<UUID> cancelledOrderIds,
            Instant plannedArrival, Instant promisedEnd) {

        public AssignedStop {
            if (seq < 1) {
                throw new IllegalArgumentException("seq 는 1 이상이어야 합니다: " + seq);
            }
            orderIds = List.copyOf(orderIds);
            cancelledOrderIds = Set.copyOf(cancelledOrderIds);
            Objects.requireNonNull(plannedArrival, "plannedArrival");
            Objects.requireNonNull(promisedEnd, "promisedEnd");
            if (orderIds.isEmpty()) {
                throw new IllegalArgumentException("주문 없는 stop 은 없습니다: seq=" + seq);
            }
        }

        /**
         * @param orderId 주문 id
         * @return 이 개정에서 취소된 주문이면 {@code true}
         */
        public boolean isCancelled(UUID orderId) {
            return cancelledOrderIds.contains(orderId);
        }
    }

    /**
     * 반영 결과.
     *
     * <p>넷을 따로 세는 이유는 로그 한 줄로 「무슨 일이 있었나」에 답하기 위해서다.
     * {@code keptTerminal} 이 0 이 아닌 것은 <em>정상</em>이다 — 재계획이 이미 배송된 주문을
     * 다시 실어 보낸 경우이고, tracking 쪽 방어선이 동작했다는 뜻이다 (§5.4).
     *
     * @param kind         적용했는가, 지난 개정이었는가
     * @param created      새로 만든 배송 수
     * @param revised      계획을 갱신한 배송 수
     * @param cancelled    이번 개정에서 취소로 옮긴 배송 수
     * @param keptTerminal 종결 상태라 그대로 둔 배송 수
     */
    record Outcome(Kind kind, int created, int revised, int cancelled, int keptTerminal) {

        /** 결과 갈래. */
        public enum Kind {
            /** 이 개정을 반영했다. */
            APPLIED,
            /** 이미 같거나 더 높은 개정을 적용했다 — 아무것도 바꾸지 않았다. */
            STALE
        }

        /** 지난 개정이었다. */
        public static Outcome stale() {
            return new Outcome(Kind.STALE, 0, 0, 0, 0);
        }
    }
}
