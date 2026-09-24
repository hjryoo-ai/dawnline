package com.dawnline.ops.application.port.out;

import java.time.Instant;

/**
 * 읽기 모델 보존 정리의 삭제와 셈 (ADR-058, DESIGN.md §5.5 「updated_at」).
 *
 * <p>삭제는 전부 <strong>오래된 행부터 {@code limit} 개</strong>를 지우고 지운 수를 돌려준다. {@code limit} 을 못
 * 채운 반환이 「대상이 소진됐다」의 유일한 신호다.
 */
public interface ReadModelRetention {

    /**
     * 종결 주문 행 — 주문 취소·배차 불가이거나 배송 결과가 있다. {@code order_status} 가 NULL 인 행은 종결이
     * 아니다(주문 접수 사실이 아직 안 왔다 — 모름은 종결이 아니다).
     *
     * @param updatedBefore 이 시각 전에 마지막으로 만진 행
     * @param limit         최대 행 수 (1 이상)
     * @return 지운 행 수
     */
    int deleteSettledOrdersUpdatedBefore(Instant updatedBefore, int limit);

    /**
     * 상태와 무관하게 {@code updated_at} 이 임계 전인 주문 행 — 상한이다. 정리이지 정책이 아니다.
     *
     * @param updatedBefore 이 시각 전에 마지막으로 만진 행
     * @param limit         최대 행 수 (1 이상)
     * @return 지운 행 수
     */
    int deleteOrdersUpdatedBefore(Instant updatedBefore, int limit);

    /**
     * 참조하는 주문 행이 없는 라우트.
     *
     * @param updatedBefore 이 시각 전에 마지막으로 만진 행
     * @param limit         최대 행 수 (1 이상)
     * @return 지운 행 수
     */
    int deleteUnreferencedRoutesUpdatedBefore(Instant updatedBefore, int limit);

    /**
     * 참조하는 주문 행이 없는 웨이브.
     *
     * @param updatedBefore 이 시각 전에 마지막으로 만진 행
     * @param limit         최대 행 수 (1 이상)
     * @return 지운 행 수
     */
    int deleteUnreferencedWavesUpdatedBefore(Instant updatedBefore, int limit);

    /**
     * 보존 기간을 넘겼는데 종결이 아닌 주문 행 수 — {@code dawnline_rm_orders_stuck} (ADR-058 결정 3).
     *
     * @param updatedBefore 보존의 임계 — 이 시각 전에 마지막으로 만진 행
     * @return 걸린 행 수
     */
    long countStuckOrdersUpdatedBefore(Instant updatedBefore);
}
