package com.dawnline.fulfillment.domain;

/**
 * 캠프의 홈 FC 가 §5.2 1~3단계 필터에서 떨어져 대체 FC 를 고른 이유 (ADR-021 결정 3-c).
 *
 * <p>{@code dawnline_fc_fallback_total{camp,reason}} 의 {@code reason} 이다. 대체가 조용히
 * 일어나면 규칙은 동작하지만 아무것도 알려 주지 않는다 — 이 값이 계속 오르는 캠프는 홈 FC 배정이
 * 잘못됐거나 그 FC 의 역량이 부족한 것이고, 그것이 §5.2 의 FC 선택 규칙이 드러내려던 사실이다.
 */
public enum FcFallbackReason {

    /** 홈 FC 가 이 티어를 지원하지 않는다. */
    TIER,

    /** 냉장이 필요한데 홈 FC 가 냉장을 지원하지 않는다. */
    COLD,

    /** 홈 FC 에 재고가 모자란다. */
    INVENTORY
}
