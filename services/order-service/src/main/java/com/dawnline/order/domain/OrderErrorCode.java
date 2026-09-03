package com.dawnline.order.domain;

import com.dawnline.common.error.ErrorCode;

/**
 * order-service 고유 오류 코드 (CLAUDE.md 「코딩 컨벤션」 — 서비스 고유 오류는 서비스에서 정의한다).
 *
 * <p>{@code CommonErrorCode} 에 없는 것만 둔다. 여기 있는 코드는 <strong>클라이언트가 코드만 보고
 * 다음 행동을 정할 수 있어야</strong> 의미가 있다 — 그러지 못하면 공통 코드로 충분하다.
 */
public enum OrderErrorCode implements ErrorCode {

    /**
     * 같은 멱등 키의 요청이 아직 처리 중이다 (§5.1 2단계, ADR-018).
     *
     * <p>{@code CommonErrorCode.CONFLICT} 와 나누는 이유: 이 409 는 <strong>잠시 후 같은 요청을
     * 그대로 다시 보내면 되는</strong> 유일한 409 다. 다른 409(이미 완료된 멱등 키, 취소 불가 상태)는
     * 재시도해도 결과가 같다. 클라이언트가 둘을 구분하지 못하면 재시도하지 말아야 할 것을 재시도하거나
     * 재시도해야 할 것을 포기한다. 응답에는 {@code Retry-After} 도 함께 붙는다.
     */
    IDEMPOTENT_REQUEST_IN_FLIGHT("idempotent-request-in-flight", 409, "같은 멱등 키의 요청이 처리 중입니다"),

    /**
     * 이 지역·시각에 그 서비스 티어를 제공하지 않는다 (§5.1 {@code TierEligibility}).
     *
     * <p>{@code VALIDATION_FAILED}(400)가 아닌 이유: 요청 값 자체는 형식·범위 모두 유효하다.
     * 처리할 수 없는 것은 <em>의미</em> 때문이다.
     */
    TIER_NOT_SERVICEABLE("tier-not-serviceable", 422, "해당 지역에 제공되지 않는 배송 티어입니다");

    private final String code;
    private final int status;
    private final String title;

    OrderErrorCode(String code, int status, String title) {
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
