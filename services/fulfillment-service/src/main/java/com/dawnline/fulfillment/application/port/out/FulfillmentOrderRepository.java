package com.dawnline.fulfillment.application.port.out;

import com.dawnline.fulfillment.domain.FulfillmentOrder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code fulfillment_orders} 저장소 ([ADR-022](docs/adr/ADR-022-fulfillment-order-aggregate.md)).
 *
 * <p>주문 하나당 행 하나이고 PK 가 {@code order_id} 단독이다. 그 사실이 이 포트의 모양을 정한다 —
 * "한 주문이 두 웨이브" 가 구조적으로 불가능하고, 동시 도착은 PK 가 직렬화한다.
 */
public interface FulfillmentOrderRepository {

    /**
     * 없으면 만든다 ({@code INSERT … ON CONFLICT DO NOTHING}).
     *
     * <p>{@code order.placed} 와 {@code order.cancelled} 는 키가 같아도 다른 토픽이라 순서가
     * 보장되지 않는다(§4.5). 두 리스너가 같은 {@code order_id} 로 동시에 들어오면 PK 에서 한쪽이
     * 대기하고, 진 쪽은 {@code false} 를 받아 재조회 후 상태 머신을 적용한다 (ADR-022 결정 4).
     *
     * @param order 새 행
     * @return 이 호출이 실제로 만들었으면 {@code true}
     */
    boolean insertIfAbsent(FulfillmentOrder order);

    /**
     * 주문 id 로 찾는다.
     *
     * @param orderId 주문 id
     */
    Optional<FulfillmentOrder> findById(UUID orderId);

    /**
     * 웨이브에 편입된 주문들 (계획 후보).
     *
     * <p>{@code ix_fulfillment_orders_wave} 를 탄다. 취소·배차 불가 행은 대상이 아니다.
     *
     * @param waveId 웨이브 id
     */
    List<FulfillmentOrder> findPlannedInWave(UUID waveId);

    /**
     * 웨이브에 편입된 주문 수 (ADR-025).
     *
     * <p>마감 시 한 번 부른다. 이 값이 {@code waves.order_count} 가 되고 {@code wave.closed} 로
     * 나간다(§4.3). 편입마다 카운터를 올리지 않는 이유는 그것이 웨이브 행에 배타 락을 요구해
     * §8.2 피크에서 병목이 되기 때문이다. <strong>세는 방식은 매번 사실에서 다시 만든다</strong> —
     * 증감 방식은 한 번 새면 그 웨이브의 숫자가 영원히 틀리고 틀렸다는 사실조차 드러나지 않는다.
     *
     * <p>{@code ix_fulfillment_orders_wave} 가 이 집계를 받는다.
     *
     * @param waveId 웨이브 id
     */
    int countPlannedInWave(UUID waveId);

    /**
     * 변경을 반영한다 (낙관적 락).
     *
     * @param order 변경된 주문
     */
    void update(FulfillmentOrder order);

    /**
     * 보존 만료 주문을 배치로 지운다 (ADR-023 결정 1 — 30일, {@code updated_at} 기준).
     *
     * <p><strong>종결 상태만</strong> 지운다. {@code CANCELLED}·{@code UNSERVICEABLE} 이거나,
     * {@code PLANNED} 이면서 소속 웨이브가 {@code PLANNED}/{@code PLAN_FAILED} 인 행이다.
     * 아직 마감되지 않은 웨이브의 주문은 진행 중이므로 나이와 무관하게 남긴다 — 30일 넘게 열려
     * 있는 웨이브는 그 자체가 사고이고, 사고 상황에서 데이터를 먼저 지우는 정리가 최악이다.
     *
     * @param updatedBefore 이 시각 이전에 마지막으로 변경된 행
     * @param limit         한 트랜잭션에서 지울 최대 행 수
     * @return 삭제된 행 수
     */
    int deleteSettledUpdatedBefore(Instant updatedBefore, int limit);
}
