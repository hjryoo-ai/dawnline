package com.dawnline.ops.domain;

import com.dawnline.common.error.ErrorCode;

/**
 * 감사 해소의 거절 (DESIGN.md §5.5 「감사 해소」, ADR-065 결정 3).
 *
 * <p>둘 다 409 — 요청은 옳은데 대상 행의 지금 상태가 받지 않는다. 운영자에게 하는 말이 달라서 코드를 가른다: 하나는 「닫을 것이
 * 없다」, 다른 하나는 「누군가 이미 닫았다 — 그 행을 봐라」.
 */
public enum AuditErrorCode implements ErrorCode {

    /** 대상이 {@code UNKNOWN} 도, 5분 넘은 {@code PENDING} 도 아니다. */
    NOT_RESOLVABLE("audit-not-resolvable", 409, "해소할 수 있는 감사 행이 아닙니다"),

    /** 이미 해소됐다 — 해소는 한 번이다. */
    ALREADY_RESOLVED("audit-already-resolved", 409, "이미 해소된 감사 행입니다");

    private final String code;
    private final int status;
    private final String title;

    AuditErrorCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
