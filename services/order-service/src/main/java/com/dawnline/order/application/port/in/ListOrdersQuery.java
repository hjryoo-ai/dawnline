package com.dawnline.order.application.port.in;

import com.dawnline.common.error.ValidationException;
import com.dawnline.order.domain.OrderStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 주문 목록 조회 조건 (DESIGN.md §5.1 {@code GET /api/v1/orders?customerId&status&from&to}).
 *
 * <p>커서를 {@code (placedAt, id)} 두 값으로 받는 이유는 {@code OrderRepository} Javadoc 과 같다 —
 * {@code placedAt} 만으로는 같은 밀리초에 접수된 주문들 사이에서 커서가 멈추지 못해 건너뛰거나
 * 반복한다. 웹 어댑터는 이 두 값을 불투명한 문자열 하나로 감싸 내보낸다.
 *
 * @param customerId     고객 id (필수)
 * @param status         상태 필터. {@code null} 이면 전체
 * @param from           접수 시각 하한(포함). {@code null} 이면 제한 없음
 * @param to             접수 시각 상한(제외). {@code null} 이면 제한 없음
 * @param cursorPlacedAt 이 시각보다 이전부터. {@code null} 이면 처음부터
 * @param cursorId       같은 시각일 때 이 id 보다 작은 것부터
 * @param limit          최대 건수
 */
public record ListOrdersQuery(
        UUID customerId,
        @Nullable OrderStatus status,
        @Nullable Instant from,
        @Nullable Instant to,
        @Nullable Instant cursorPlacedAt,
        @Nullable UUID cursorId,
        int limit) {

    /** 한 페이지 기본 건수. */
    public static final int DEFAULT_LIMIT = 20;

    /** 한 페이지 최대 건수. 이보다 크게 요청하면 거부한다 — 조용히 줄이면 클라이언트가 끝을 오판한다. */
    public static final int MAX_LIMIT = 100;

    public ListOrdersQuery {
        Objects.requireNonNull(customerId, "customerId");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ValidationException.field("limit", limit, "1.." + MAX_LIMIT + " 이어야 합니다");
        }
        if (from != null && to != null && !to.isAfter(from)) {
            throw ValidationException.field("to", to, "from 보다 뒤여야 합니다");
        }
        if ((cursorPlacedAt == null) != (cursorId == null)) {
            throw ValidationException.field("cursor", "", "시각과 id 를 함께 주어야 합니다");
        }
    }
}
