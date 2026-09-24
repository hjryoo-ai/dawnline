package com.dawnline.ops.domain;

/**
 * 운영자 커맨드 한 건의 결과 — {@code audit_logs.result} (DESIGN.md §5.5 「커맨드 위임」, §4.6 「DLQ 재처리」).
 *
 * <p>아래 설명은 위임의 말이다. DLQ 재처리에서는 「코어」가 브로커다 — ack 는 {@link #SUCCEEDED}, 보내기 전 거절은
 * {@link #REJECTED}, 브로커의 재시도 불가 오류는 {@link #FAILED}, 그 밖은 {@link #UNKNOWN}(ADR-053 결정 4).
 *
 * <p>감사 행은 위임 <em>전에</em> {@link #PENDING} 으로 쓰이고, 위임이 끝나면 나머지 넷 중 하나로 한 번
 * 닫힌다. 넷의 경계는 「코어에 적용됐는가」를 <strong>아는가</strong>로 긋는다 — 모름을 값으로 접지 않는다.
 */
public enum AuditResult {

    /** 기록했고 아직 위임이 끝나지 않았다. 오래 남아 있으면 ops-api 가 그 사이에 죽었다(RB-07). */
    PENDING,

    /** 코어가 2xx 로 답했다 — 적용됐다. */
    SUCCEEDED,

    /** 코어가 4xx 로 거절했다 — 적용되지 않았다. */
    REJECTED,

    /** 연결이 맺어지지 않았다 — 요청이 코어에 닿지 않은 것이 확실한 유일한 경우다. */
    FAILED,

    /**
     * 적용됐는지 모른다 — 응답 전 타임아웃, 응답 도중 끊김, 코어의 5xx. 5xx 는 「무언가 깨졌다」이지
     * 「아무 일도 없었다」가 아니다. 사람이 감사 id 로 코어 로그를 보고 닫는다(RB-07).
     */
    UNKNOWN
}
