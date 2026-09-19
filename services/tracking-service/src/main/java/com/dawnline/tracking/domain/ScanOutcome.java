package com.dawnline.tracking.domain;

/**
 * 스캔 하나를 적용한 결과 (DESIGN.md §5.4).
 *
 * <p>셋을 나누는 이유는 <strong>세는 자리가 다르기 때문</strong>이다. {@code STALE} 은 정상이고
 * 늘 조금씩 생기지만, {@link #AFTER_CANCEL} 은 사람이 봐야 하는 상황이다 — 둘을 한 값으로 합치면
 * 알림을 걸 수 없다(§9.1 의 {@code stale} 대 {@code rejected} 와 같은 구분).
 */
public enum ScanOutcome {

    /** 상태가 옮겨졌다. */
    APPLIED,

    /**
     * 이미 지나온 지점이라 무시했다 — 중복 스캔이거나 순서가 뒤바뀌어 늦게 온 것이다.
     * 사실은 이미 일어났고, 순서가 다른 것은 우리가 알게 된 순서일 뿐이다
     * ([ADR-017](docs/adr/ADR-017-order-state-machine-absorbs-out-of-order-events.md)).
     */
    STALE,

    /**
     * 취소된 배송에 도착한 스캔이라 무시했다 — <strong>세어야 하는 쪽</strong>이다
     * ({@code dawnline_scan_after_cancel_total}). 기사가 취소를 받지 못하고 배송한 경우이고,
     * dispatch 의 {@code dawnline_cancel_too_late_total}(§6.10 넷째 분기)과 한 쌍이다.
     */
    AFTER_CANCEL
}
