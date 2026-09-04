package com.dawnline.fulfillment.domain;

import java.util.Objects;
import java.util.Set;

/**
 * fulfillment 가 보는 주문 상태 (ADR-022).
 *
 * <pre>
 * (행 없음) ──(order.placed, 계획 성공)──▶ PLANNED ─────────┐
 *     │     ──(order.placed, 계획 불가)──▶ UNSERVICEABLE ───┼──(order.cancelled)──▶ CANCELLED
 *     └─────(order.cancelled 선착)───────────────────────────┘
 * </pre>
 *
 * <h2>축 규칙 (ADR-017 · 커밋 {@code 4a44df4})</h2>
 * order-service 의 {@code OrderStatus} 와 같은 규칙을 쓴다 — <strong>진행 축에서 앞으로 가는
 * 전이는 건너뛰어도 허용하고, 이미 지나온 지점으로의 전이는 무시하고 stale 로 센다.</strong>
 * 순서가 뒤바뀐 것은 사실이 뒤바뀐 것이 아니라 우리가 알게 된 순서가 다를 뿐이기 때문이다.
 *
 * <p>축은 <em>판정 진행도</em>다.
 * <pre>
 *   (행 없음) 0  →  PLANNED · UNSERVICEABLE 1  →  CANCELLED 2
 * </pre>
 *
 * <h2>취소 선착이 이 축의 한 사례다</h2>
 * {@code order.cancelled} 가 {@code order.placed} 보다 먼저 오면(§4.5 — 키는 같아도 토픽이 달라
 * 순서가 보장되지 않는다) {@code CANCELLED}(2) 행이 먼저 생긴다. 뒤늦게 온 {@code order.placed}
 * 는 {@code PLANNED}(1)/{@code UNSERVICEABLE}(1)을 요구하는데, 그것은 <strong>이미 지나온
 * 지점</strong>이므로 축 규칙이 그대로 무시한다.
 *
 * <p><strong>그래서 별도의 취소 마커 테이블이 필요 없다.</strong> 마커는 이 축 위의 한 상태
 * ({@code CANCELLED} + {@code placed_event_id IS NULL})일 뿐이고, "무시" 는 새로 만든 분기가
 * 아니라 이미 있는 규칙의 결과다.
 *
 * <h2>order-service 와 다른 점 — {@code CANCELLED} 가 축 <em>위에</em> 있다</h2>
 * order-service 에서 {@code CANCELLED} 는 축 밖({@code -1})이다. 취소된 주문에 배송 이벤트가
 * 오는 것은 <em>실제로 잘못된 상황</em>(취소된 소포가 차에 실려 있다)이라 조용히 버리면 안 되기
 * 때문이다. 여기서는 반대다 — 취소된 주문에 {@code order.placed} 가 오는 것은 <strong>정상적인
 * 순서 뒤바뀜</strong>이고, 알림이 아니라 흡수의 대상이다. 같은 규칙을 쓰되 축 위의 자리가 다른
 * 이유는 <em>같은 사건 쌍이 두 서비스에서 다른 뜻</em>이기 때문이다.
 */
public enum FulfillmentOrderStatus {

    /** FC·캠프·권역·웨이브가 정해졌다. */
    PLANNED,

    /** 배차할 수 없다 (§5.2 6단계). 사유는 {@link UnserviceableReason}. */
    UNSERVICEABLE,

    /** 취소됐다. 축의 최고점이자 종료 상태다. */
    CANCELLED;

    /** 이 상태에서 갈 수 있는 다음 상태들. */
    public Set<FulfillmentOrderStatus> allowedTransitions() {
        return switch (this) {
            // 판정이 끝난 뒤에도 취소는 온다(취소 후착). 축에서 앞으로 가는 전이다.
            case PLANNED, UNSERVICEABLE -> Set.of(CANCELLED);
            // 종료. 취소된 주문의 재접수는 새 주문이지 이 주문의 전이가 아니다.
            case CANCELLED -> Set.of();
        };
    }

    /**
     * 판정 진행 단계. {@code PLANNED}·{@code UNSERVICEABLE} 은 같은 1이다 — 둘 다
     * "{@code order.placed} 를 처리한 결과" 이고 서로를 덮어쓸 이유가 없다.
     */
    public int progress() {
        return switch (this) {
            case PLANNED, UNSERVICEABLE -> 1;
            case CANCELLED -> 2;
        };
    }

    /**
     * {@code target} 이 <strong>이미 지나온 지점</strong>인가 (ADR-017 축 규칙).
     *
     * <p>참이면 그 이벤트는 순서가 뒤바뀌어 늦게 도착한 것이므로 무시하고 커밋한다.
     * 취소 선착 뒤에 오는 {@code order.placed} 가 정확히 이 경우다.
     *
     * @param target 이벤트가 요구하는 상태
     */
    public boolean hasProgressedPast(FulfillmentOrderStatus target) {
        Objects.requireNonNull(target, "target");
        return target.progress() <= progress();
    }

    /**
     * {@code next} 로 전이할 수 있는가.
     *
     * <p>같은 상태로의 전이는 허용하지 않는다. 중복 이벤트는 {@code processed_events} 가 리스너
     * 앞단에서 거르므로(불변규칙 2) 여기까지 오지 않고, 여기서 조용히 통과시키면 진짜 잘못된
     * 전이까지 함께 숨는다.
     *
     * @param next 목표 상태
     */
    public boolean canTransitionTo(FulfillmentOrderStatus next) {
        return allowedTransitions().contains(next);
    }

    /** 더 이상 전이가 없는 종료 상태인가. */
    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }

    /** {@code fulfillment_orders} 정리 배치가 지울 수 있는 상태인가 (ADR-023). */
    public boolean isSettledWithoutWave() {
        return this == CANCELLED || this == UNSERVICEABLE;
    }
}
