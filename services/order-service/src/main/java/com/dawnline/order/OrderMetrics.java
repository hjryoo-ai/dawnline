package com.dawnline.order;

/**
 * order-service 가 등록하는 Micrometer 메트릭·태그 이름 (DESIGN.md §9.1).
 *
 * <p>이름 대응은 {@code libs/messaging} 의 {@code MessagingMetrics} 와 같은 규칙이다 —
 * Micrometer 는 점 표기를 쓰고 Prometheus 레지스트리가 {@code _} 로 바꾸며 counter 에
 * {@code _total} 을 붙인다.
 *
 * <table>
 *   <caption>이름 대응</caption>
 *   <tr><th>여기</th><th>Prometheus (§9.1)</th></tr>
 *   <tr><td>dawnline.orders.placed</td><td>dawnline_orders_placed_total</td></tr>
 *   <tr><td>dawnline.rate.limit.decisions</td><td>dawnline_rate_limit_decisions_total</td></tr>
 * </table>
 */
public final class OrderMetrics {

    /**
     * counter — 레이트 리밋 판정 (§7.2, §9.1). 태그: outcome.
     *
     * <p>{@code bypassed} 는 <strong>Redis 장애로 판정을 건너뛴 것</strong>이다. 인증이 없는
     * API(§10)에서 레이트 리밋은 유일한 남용 방지 수단이므로, 그것이 조용히 꺼진 상태를 반드시
     * 보이게 해야 한다. §9.4 가 이 값의 증가에 알림을 건다.
     */
    public static final String RATE_LIMIT_DECISIONS = "dawnline.rate.limit.decisions";

    /**
     * counter — 접수된 주문 수 (§9.1). 태그: tier.
     *
     * <p><strong>재생은 세지 않는다.</strong> 같은 멱등 키의 재요청은 새 주문이 아니라 이미 센 주문의
     * 응답을 다시 주는 것이다. 그것까지 세면 클라이언트의 재시도 패턴이 주문량 지표를 부풀린다.
     *
     * <p>§9.1 의 라벨에 {@code camp} 가 없는 이유: 캠프는 fulfillment-service 가 정하므로
     * (§5.2 FC·캠프 선택) 접수 시점에는 알 수 없다. 캠프별 유입은 {@code dawnline_wave_orders} 가 본다.
     */
    public static final String ORDERS_PLACED = "dawnline.orders.placed";

    /** 태그: 판정 결과. {@code allowed} / {@code limited} / {@code bypassed}. */
    public static final String TAG_OUTCOME = "outcome";

    /** 태그: 서비스 티어 (§2.2). */
    public static final String TAG_TIER = "tier";

    private OrderMetrics() {
        throw new AssertionError("유틸리티 클래스는 생성하지 않는다");
    }
}
