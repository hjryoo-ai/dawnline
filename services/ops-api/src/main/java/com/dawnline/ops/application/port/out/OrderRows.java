package com.dawnline.ops.application.port.out;

import com.dawnline.ops.domain.DeliveryOutcome;
import com.dawnline.ops.domain.OrderStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code rm_orders} (DESIGN.md §5.5).
 *
 * <p>「행을 만드는 핸들러」가 없다(ADR-051 결정 1). {@link #lock} 이 없는 행을 <strong>키만</strong>
 * 가진 채로 만들고, 그 뒤의 쓰기는 전부 {@link #write} 다. 그래서 늦게 온 사실의 쓰기가 0 행을
 * 갱신하고 조용히 성공하는 자리가 없다.
 */
public interface OrderRows {

    /**
     * 없는 행을 키만으로 만들고, 전부를 {@code order_id} 순서로 잠근 뒤 판정에 필요한 칸을 돌려준다.
     *
     * <p>순서가 계약이다 — 여러 행을 잠그는 트랜잭션끼리 같은 순서로 잠가야 교착하지 않는다.
     *
     * @param orderIds 주문들 (중복 허용)
     * @return 주문 → 판정용 현재 값
     */
    Map<UUID, OrderRow> lock(Collection<UUID> orderIds);

    /**
     * 한 행에 패치를 적는다. {@link #lock} 으로 잠근 행에만 부른다.
     *
     * @param orderId   주문
     * @param patch     적을 칸 — 비어 있으면 아무것도 하지 않는다
     * @param touchedAt {@code updated_at} — 사실이 아니라 프로젝션의 기록이다
     */
    void write(UUID orderId, Patch<OrderColumn> patch, Instant touchedAt);

    /**
     * 판정에 필요한 현재 값.
     *
     * @param orderStatus     주문 쪽 축
     * @param deliveryOutcome 배송 결과
     * @param routeId         지금 계획상 라우트
     * @param plannedAsOf     계획 칸을 쓴 {@code route.assigned} 의 발행 시각
     * @param etaAsOf         ETA 를 쓴 at-risk 의 판정 시각
     */
    record OrderRow(@Nullable OrderStatus orderStatus, @Nullable DeliveryOutcome deliveryOutcome,
            @Nullable UUID routeId, @Nullable Instant plannedAsOf, @Nullable Instant etaAsOf) {
    }
}
