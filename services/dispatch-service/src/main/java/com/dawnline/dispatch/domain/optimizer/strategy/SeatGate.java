package com.dawnline.dispatch.domain.optimizer.strategy;

import com.dawnline.dispatch.domain.optimizer.Feasibility;
import com.dawnline.dispatch.domain.optimizer.Stop;

/**
 * 좌석 예약이 이 stop 에 자리를 여는가 ([ADR-039]).
 *
 * <p>하드 룰이 아니라 <strong>배정 단계의 하드 용량</strong>이다. 룰이 아닌 이유는 §6.3 이 룰을
 * 데이터로 뒀기 때문이다 — 예약은 이 웨이브의 수요와 함대에서 <em>계산되는</em> 값이지 운영자가
 * 적는 값이 아니다. 그리고 <strong>배정이 끝나면 풀린다</strong>: 재삽입과 개선 단계는 이 문을
 * 보지 않는다({@link #OPEN}).
 */
interface SeatGate {

    /** 아무것도 막지 않는 문. 예약이 없을 때, 그리고 예약을 푼 뒤가 이것이다. */
    SeatGate OPEN = new SeatGate() {

        @Override
        public Feasibility admits(Stop stop) {
            return Feasibility.ok();
        }

        @Override
        public void seat(Stop stop) {
            // 셀 것이 없다.
        }
    };

    /**
     * 이 stop 이 앉을 자리가 있는가. 하드 룰과 <strong>따로</strong> 묻는다 — 룰은 「이 차가
     * 실을 수 있는가」이고 이것은 「이 자리가 이 수요의 것인가」다.
     *
     * @param stop 앉히려는 stop
     */
    Feasibility admits(Stop stop);

    /**
     * 실제로 앉혔다고 알린다. {@link #admits} 가 다음 판정에서 이 자리를 셈에 넣는다.
     *
     * @param stop 앉힌 stop
     */
    void seat(Stop stop);
}
