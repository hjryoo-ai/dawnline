package com.dawnline.ops.adapter.in.web;

import com.dawnline.common.error.ErrorCode;

/**
 * 코어를 부르다 ops-api 가 스스로 내는 오류 — 코어의 거절(4xx)은 여기 없다, 그것은 바이트 그대로 옮긴다
 * (DESIGN.md §5.5 「커맨드 위임」).
 *
 * <p>상수 문자열이던 셋을 {@link ErrorCode} 로 둔 이유: ops-api 가 내는 코드가 전부 {@code ErrorCode} 구현이어야 문서 검사
 * ({@code OpenApiContractIT} — 응답 설명에 ops-api 밖의 코드가 없다)가 그 집합을 열거하지 않고 코드에서 읽는다(ADR-052 후속).
 */
enum CoreCallErrorCode implements ErrorCode {

    /** 연결이 맺어지지 않았다 — 적용되지 않았다(감사 {@code FAILED}). */
    CORE_UNREACHABLE("core-unreachable", 502, "코어에 연결하지 못했습니다"),

    /** 응답 전 타임아웃 — 적용됐는지 모른다(감사 {@code UNKNOWN}). */
    CORE_TIMEOUT("core-timeout", 504, "코어가 제시간에 답하지 않았습니다"),

    /** 코어의 5xx · 응답 도중 끊김 — 적용됐는지 모른다(감사 {@code UNKNOWN}). */
    CORE_ERROR("core-error", 502, "코어가 오류로 답했습니다");

    private final String code;
    private final int status;
    private final String title;

    CoreCallErrorCode(String code, int status, String title) {
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
