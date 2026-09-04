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

    /** 태그: 판정 결과. {@code allowed} / {@code limited} / {@code bypassed}. */
    public static final String TAG_OUTCOME = "outcome";

    private OrderMetrics() {
        throw new AssertionError("유틸리티 클래스는 생성하지 않는다");
    }
}
