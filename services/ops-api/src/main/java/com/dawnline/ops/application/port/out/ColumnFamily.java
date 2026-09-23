package com.dawnline.ops.application.port.out;

/**
 * 칸의 계열 — <strong>무엇이 그 칸의 판정 키인가</strong> (DESIGN.md §5.5 「판정 키」).
 *
 * <p>ADR-047 의 「계획은 {@code (route, revision, seq)} 로, 사실은 {@code orderId} 로」를 읽기
 * 모델로 옮긴 것이다. 한 행에 두 계열이 같이 앉아 있으므로(주문 행에 계획 도착과 배송 결과가,
 * 라우트 행에 계획 거리와 출발 시각이) 칸마다 어느 계열인지 적어 두고, 두 계열을 섞어 쓰는
 * 핸들러가 없는지를 {@code ColumnFamilyTest} 가 본다.
 */
public enum ColumnFamily {

    /** 주문 쪽 사실 — order-service·fulfillment 의 토픽이 쓴다. 상태는 주문 쪽 축으로 판정한다. */
    ORDER,

    /**
     * 계획 — dispatch 의 계획 토픽이 쓴다. 라우트 안에서는 {@code revision} 이, 라우트를 넘는 주문의
     * 계획 칸은 계획 이벤트의 {@code occurredAt}(생산자의 시계)이 판정한다.
     */
    PLAN,

    /** 추적 — tracking 의 토픽이 쓴다. 그 사실의 사건 시각(생산자의 시계) 또는 추적 축이 판정한다. */
    TRACKING,

    /** 키를 이루는 불변 속성 — 사본을 싣는 토픽 모두가 쓰고 먼저 온 것이 남는다. */
    KEY,

    /** 여러 계열이 설계상 함께 쓰는 상태 칸 — 축이 판정한다(ADR-051 결정 3). */
    AXIS
}
