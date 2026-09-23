package com.dawnline.tracking.domain;

import java.util.Objects;
import java.util.Set;

/**
 * 배송 상태 (DESIGN.md §5.4 상태 머신).
 *
 * <pre>
 * SCHEDULED ─(DEPARTED_CAMP)─▶ OUT_FOR_DELIVERY ─(ARRIVED)─▶ ARRIVED ─(COMPLETED)─▶ COMPLETED
 *     │               │                                          └─(FAILED)──────▶ FAILED
 *     └───────────────┴──(route.assigned 의 취소)──▶ CANCELLED        (ARRIVED 이후 취소 불가)
 * </pre>
 *
 * <p>전이 규칙을 <strong>상태 자신이</strong> 안다. {@link Shipment} 의 전이 메서드가
 * {@code if (status != X)} 를 나열하면 표가 코드 여기저기로 흩어진다 — order-service 의
 * {@code OrderStatus} 와 같은 형태이고, 같은 이유다(불변규칙 6).
 *
 * <h2>축 규칙의 셋째 자리</h2>
 * order-service([ADR-017](docs/adr/ADR-017-order-state-machine-absorbs-out-of-order-events.md)) ·
 * fulfillment 에 이어 여기가 세 번째다. 규칙은 같다 — <strong>앞으로 가는 건너뜀은 받아들이고,
 * 이미 지나온 지점으로의 이벤트는 무시한다.</strong> 다른 것은 자리뿐이다.
 *
 * <p>건너뜀을 받아들이는 이유가 여기서는 특히 분명하다: 기사가 도착 스캔을 빼먹고 완료만 찍는
 * 일은 흔하고, 그때 물건은 <em>실제로</em> 전달됐다. 중간 상태를 안 거친 것은 그 사건을 알리는
 * 스캔이 없었다는 뜻이지 배송이 안 됐다는 뜻이 아니다.
 *
 * <h2>{@code CANCELLED} 는 진행 축 위에 없다</h2>
 * {@code OrderStatus.CANCELLED} 와 같은 이유로 {@code -1} 이다. 그리고 같은 이유로
 * <strong>그 뒤에 오는 스캔을 축으로 흡수하지 않는다</strong> — 흡수하면 「취소된 주문이
 * 배송됐다」가 stale 로 섞여 보이지 않게 된다. 그 건은 세어야 한다
 * ({@code dawnline_scan_after_cancel_total}, §9.1).
 */
public enum ShipmentStatus {

    /** 라우트에 실렸고 아직 출발 전이다. {@code route.assigned} 를 받으면 여기서 시작한다. */
    SCHEDULED,

    /** 캠프를 떠났다 ({@code DEPARTED_CAMP} 스캔). */
    OUT_FOR_DELIVERY,

    /** 배송지에 도착했다 ({@code ARRIVED} 스캔). */
    ARRIVED,

    /** 전달 완료. */
    COMPLETED,

    /** 전달 실패(부재·주소 오류 등). */
    FAILED,

    /** 취소됨. 배송하지 않는다. */
    CANCELLED;

    /** 이 상태에서 갈 수 있는 다음 상태들. */
    public Set<ShipmentStatus> allowedTransitions() {
        return switch (this) {
            // 진행 축에서 앞으로 가는 전이는 전부 허용한다 — 건너뜀은 오류가 아니라
            // 스캔 누락이다. 취소는 ARRIVED 전까지만 받는다(§6.10 의 분기와 같은 경계).
            case SCHEDULED -> Set.of(OUT_FOR_DELIVERY, ARRIVED, COMPLETED, FAILED, CANCELLED);
            case OUT_FOR_DELIVERY -> Set.of(ARRIVED, COMPLETED, FAILED, CANCELLED);
            // 도착한 뒤에는 취소할 수 없다. dispatch 가 그 취소를 이미 거부하고
            // dawnline_cancel_too_late_total 로 센다(§6.10 넷째 분기).
            case ARRIVED -> Set.of(COMPLETED, FAILED);
            // 종료 상태. 재시도는 새 배송이지 이 배송의 전이가 아니다.
            case COMPLETED, FAILED, CANCELLED -> Set.of();
        };
    }

    /**
     * 배송 진행 단계.
     *
     * <p>{@code SCHEDULED(0) → OUT_FOR_DELIVERY(1) → ARRIVED(2) → COMPLETED·FAILED(3)}.
     * 스캔이 "이미 지나온 지점인가" 를 판단하는 축이다. {@code CANCELLED} 는 축 밖({@code -1}).
     *
     * @return 진행 단계. 축 밖이면 {@code -1}
     */
    public int progress() {
        return switch (this) {
            case SCHEDULED -> 0;
            case OUT_FOR_DELIVERY -> 1;
            case ARRIVED -> 2;
            case COMPLETED, FAILED -> 3;
            case CANCELLED -> -1;
        };
    }

    /**
     * {@code target} 이 <strong>이미 지나온 지점</strong>인가.
     *
     * <p>참이면 그 스캔은 늦게 도착했거나 중복이므로 무시한다. 같은 지점도 참이다 — 같은 스캔이
     * 두 번 와도 상태가 다시 움직이지 않는다(§8.5 의 「(orderIds, type) + 상태 머신」).
     *
     * <p>둘 중 하나라도 축 밖({@code CANCELLED})이면 거짓이다 — 비교할 축이 없다. 취소 뒤의 스캔은
     * 이 물음이 아니라 {@link Shipment#recordScan} 의 앞선 분기가 답한다.
     *
     * @param target 스캔이 요구하는 상태
     * @return 이미 지나왔으면 {@code true}
     */
    public boolean hasProgressedPast(ShipmentStatus target) {
        Objects.requireNonNull(target, "target");
        if (progress() < 0 || target.progress() < 0) {
            return false;
        }
        return target.progress() <= progress();
    }

    /**
     * {@code next} 로 전이할 수 있는가.
     *
     * @param next 목표 상태
     * @return 허용되면 {@code true}
     */
    public boolean canTransitionTo(ShipmentStatus next) {
        Objects.requireNonNull(next, "next");
        return allowedTransitions().contains(next);
    }

    /**
     * 더 이상 전이가 없는 종료 상태인가.
     *
     * <p>개정({@code route.assigned} 재발행)이 <strong>되돌리지 않는</strong> 상태이기도 하다
     * ({@link Shipment#applyRevision}).
     *
     * @return 종료 상태면 {@code true}
     */
    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}
