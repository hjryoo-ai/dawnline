package com.dawnline.ops.domain;

/**
 * 감사 해소의 판정 — 그 커맨드가 코어에 <strong>적용됐는가</strong> (DESIGN.md §5.5 「감사 해소」, ADR-065 결정 2).
 *
 * <p>{@link AuditResult} 의 이름을 빌리지 않는다. {@link AuditResult#FAILED} 는 「연결이 맺어지지 않았다」이고, 해소가 말하는 것은
 * 그것보다 넓다 — 연결은 맺어졌는데 코어가 DB 에 못 들어가 적용되지 않은 조기 마감도 {@link #NOT_APPLIED} 다. 이름을 빌리면 같은
 * 칸의 같은 값이 행마다 다른 뜻이 된다.
 */
public enum AuditResolution {

    /** 적용됐다. */
    APPLIED,

    /** 적용되지 않았다. */
    NOT_APPLIED
}
