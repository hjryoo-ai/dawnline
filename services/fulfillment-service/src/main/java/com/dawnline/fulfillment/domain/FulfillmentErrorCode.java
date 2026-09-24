package com.dawnline.fulfillment.domain;

import com.dawnline.common.error.ErrorCode;

/**
 * fulfillment-service 고유 오류 코드 (CLAUDE.md 「코딩 컨벤션」 — 서비스 고유 오류는 서비스에서 정의한다).
 *
 * <p>{@code CommonErrorCode} 에 없는 것만 둔다. 여기 있는 코드는 <strong>클라이언트가 코드만 보고 다음 행동을
 * 정할 수 있어야</strong> 의미가 있다 — 그러지 못하면 공통 코드로 충분하다.
 */
public enum FulfillmentErrorCode implements ErrorCode {

    /**
     * 닫으려는 웨이브가 이미 {@code OPEN} 이 아니다 (ADR-054 결정 6).
     *
     * <p>{@code illegal-state-transition} 과 나누는 이유: 이 409 는 <strong>감사 {@code UNKNOWN} 을 닫는
     * 근거</strong>다. 응답을 못 받은 마감을 다시 누른 사람은 이 코드와 함께 온 {@code closeCause} 로 앞의
     * 요청이 적용됐는지({@code MANUAL}) 스케줄러가 먼저 닫았는지({@code SCHEDULED})를 읽는다. 일반 전이
     * 오류와 섞이면 그 읽기가 「본문의 칸이 있으면」이라는 조건부가 된다.
     */
    WAVE_NOT_OPEN("wave-not-open", 409, "웨이브가 이미 닫혀 있습니다");

    private final String code;
    private final int status;
    private final String title;

    FulfillmentErrorCode(String code, int status, String title) {
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
