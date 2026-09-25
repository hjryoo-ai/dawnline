package com.dawnline.order;

/**
 * order-service 의 라벨 <strong>키</strong> (DESIGN.md §9.1).
 *
 * <p>메트릭 이름 · 타입 · 라벨 집합은 카탈로그({@code DawnlineMetrics.ORDERS_PLACED} · {@code IDEMPOTENT_REPLAYS} ·
 * {@code RATE_LIMIT_DECISIONS})에 있다(ADR-060). 각 값의 뜻은 §9.1 의 행과 세는 자리의 주석에 있다 — 접수 수는 재생을
 * 세지 않고(재시도 패턴이 주문량을 부풀린다), 재생 수는 접수 수와 <em>함께</em> 볼 때 의미가 있고, 레이트 리밋의
 * {@code bypassed} 는 Redis 장애로 남용 방지가 조용히 꺼진 상태다(§9.4 알림).
 */
public final class OrderMetrics {

    /** 태그: 판정 결과. {@code allowed} / {@code limited} / {@code bypassed}. */
    public static final String TAG_OUTCOME = "outcome";

    /** 태그: 서비스 티어 (§2.2). */
    public static final String TAG_TIER = "tier";

    private OrderMetrics() {
        throw new AssertionError("유틸리티 클래스는 생성하지 않는다");
    }
}
