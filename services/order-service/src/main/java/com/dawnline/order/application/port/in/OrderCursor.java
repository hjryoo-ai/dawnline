package com.dawnline.order.application.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 커서 위치 — "여기까지 봤다" (DESIGN.md §5.1).
 *
 * <p>불투명 문자열로 만드는 것은 웹 어댑터의 일이다. 여기서는 <em>무엇으로 이루어져 있는지</em>만
 * 정한다 — 인코딩은 API 표면의 관심사이고, 유스케이스는 두 값만 알면 된다.
 *
 * @param placedAt 마지막으로 본 주문의 접수 시각
 * @param orderId  같은 시각일 때를 가르는 두 번째 키
 */
public record OrderCursor(Instant placedAt, UUID orderId) {

    public OrderCursor {
        Objects.requireNonNull(placedAt, "placedAt");
        Objects.requireNonNull(orderId, "orderId");
    }
}
