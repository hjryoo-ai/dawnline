package com.dawnline.fulfillment.domain;

/**
 * 누가 웨이브를 닫았는가 (DESIGN.md §5.2, ADR-054 결정 3).
 *
 * <p><strong>파생하지 않고 저장한다.</strong> {@code closedAt < cutoffAt + grace} 로 판정하면 「스케줄러는 그 전에
 * 닫지 않는다」는 동작과 grace 설정값에 기대게 되고, grace 를 바꾸는 날 과거의 판정이 움직인다. 이 값은
 * {@code dawnline_promise_revised_total{cause}} 의 출처다 — 개정이 grace 로 흡수하지 못한 지연인지, 운영자가
 * 앞당긴 컷오프의 대가인지를 가른다.
 */
public enum WaveCloseCause {

    /** 컷오프 스케줄러가 {@code cutoffAt + grace} 에 닫았다. */
    SCHEDULED,

    /** 운영자가 닫았다 — 컷오프 전일 수 있다. 이유는 ops-api 의 감사 행에 있다. */
    MANUAL
}
