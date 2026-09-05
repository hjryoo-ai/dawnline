package com.dawnline.dispatch.domain.optimizer;

import java.util.Objects;
import java.util.UUID;

/**
 * 주문 식별자 (DESIGN.md §6.2).
 *
 * <h2>왜 여기만 id 를 감싸는가</h2>
 * 저장소의 다른 곳은 raw {@code UUID} 를 쓴다. 이 패키지만 다른 이유는 여기가 <strong>여러 종류의
 * id 가 한 함수 안에서 섞이는 유일한 곳</strong>이기 때문이다. {@code Map<OrderId, …>} 와
 * {@code Map<VehicleId, …>} 가 나란히 있고 {@code assign(orderId, vehicleId)} 같은 서명이 있는
 * 자리에서 raw {@code UUID} 는 인자를 바꿔 넣어도 조용히 컴파일된다.
 *
 * <p>애그리거트 하나의 id 만 다루는 다른 서비스에는 그 위험이 없으므로 그쪽은 그대로 둔다 —
 * 일관성보다 위험이 있는 곳에 방어를 두는 쪽을 골랐다.
 *
 * @param value UUIDv7 (불변규칙 10)
 */
public record OrderId(UUID value) {

    public OrderId {
        Objects.requireNonNull(value, "value");
    }

    /** 읽기 쉬운 별칭. */
    public static OrderId of(UUID value) {
        return new OrderId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
