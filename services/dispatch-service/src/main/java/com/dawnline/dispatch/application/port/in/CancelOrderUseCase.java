package com.dawnline.dispatch.application.port.in;

import java.time.Instant;
import java.util.UUID;

/**
 * 취소 처리 (DESIGN.md §6.10, ADR-026).
 *
 * <p>취소는 <strong>최적화의 트리거가 아니라 입력 변경</strong>이다. dispatch 는 stop 을 죽이고
 * 이후 stop 의 시간을 재전파할 뿐, 남은 경로를 다시 풀지 않는다 — 다시 풀 가치가 있는지는
 * revision 을 받은 tracking 의 ETA 재계산이 정한다(§6.8). 트리거를 늘리면 같은 판단을 하는 회로가
 * 둘이 되어 갈라진다.
 */
public interface CancelOrderUseCase {

    /**
     * 취소를 반영한다.
     *
     * @param orderId     취소된 주문
     * @param cancelledAt 취소 시각 ({@code order.cancelled.cancelledAt} — 우리가 처리한 시각이
     *                    아니라 사건이 일어난 시각이다)
     */
    Outcome cancel(UUID orderId, Instant cancelledAt);

    /**
     * 무엇을 했는가. 분기가 넷이라는 사실 자체가 §6.10 의 표이고, 그 표를 코드가 이름으로
     * 말하게 둔다 — 로그의 문자열로만 남기면 테스트가 "무엇이 일어났는지" 를 어설션할 수 없다.
     */
    enum Outcome {

        /** 이 서비스의 후보가 아니다. 다른 캠프의 주문이거나 배차 불가로 끝난 주문이다. */
        NOT_A_CANDIDATE,

        /** 이미 취소돼 있었다. at-least-once 재전달의 정상 결과다 (불변규칙 2 와 두 겹). */
        ALREADY_CANCELLED,

        /**
         * 후보만 취소했다 (§6.10 첫째·둘째 행). 아직 라우트에 실리지 않았거나, 계획이 도는
         * 중이라 발행 직전 재검증({@code PlanPruner}, §6.5 6단계)이 뺄 것이다.
         */
        CANDIDATE_CANCELLED,

        /**
         * 발행된 라우트에서 뺐다 (§6.10 셋째 행). stop 시간을 재전파하고 {@code route.assigned}
         * 를 개정 번호와 함께 다시 냈다.
         */
        ROUTE_REVISED,

        /**
         * 거부했다 (§6.10 넷째 행). stop 이 이미 {@code ARRIVED}/{@code COMPLETED} 라 물건이
         * 전달된 뒤다. 상태를 바꾸지 않고 {@code dawnline_cancel_too_late_total} 만 올린다 —
         * 물리적 배송과 주문 상태가 어긋난 것을 사람이 보게 하는 것이 이 분기의 역할이다.
         */
        TOO_LATE
    }
}
