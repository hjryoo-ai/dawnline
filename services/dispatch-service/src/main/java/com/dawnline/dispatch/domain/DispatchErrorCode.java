package com.dawnline.dispatch.domain;

import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.ErrorCode;
import java.util.Map;
import java.util.UUID;

/**
 * dispatch-service 고유 오류 코드 (CLAUDE.md 「코딩 컨벤션」 — 서비스 고유 오류는 서비스에서 정의한다).
 *
 * <p>{@code CommonErrorCode} 에 없는 것만 둔다. 여기 있는 코드는 <strong>클라이언트가 코드만 보고 다음 행동을
 * 정할 수 있어야</strong> 의미가 있다 — 그러지 못하면 공통 코드로 충분하다.
 */
public enum DispatchErrorCode implements ErrorCode {

    /**
     * 라우트를 다시 쓰려는데 그 주문의 후보가 없다 — 보존 정리가 지웠다 (ADR-059 결정 3).
     *
     * <p>{@code conflict} 와 나누는 이유: 이 409 는 <strong>재시도해도, 룰을 고쳐도 달라지지 않는다</strong>. 계획의
     * 근거(화물 · 약속창)가 사라진 라우트는 다시 풀 수 없고, 운영자가 할 일은 그 계획을 새로 돌리는 것뿐이다. 무시하면
     * 후보 없는 stop 이 라우트에서 <em>조용히</em> 빠진다 — 이 코드가 그 자리를 소리 나게 한다.
     */
    CANDIDATES_EXPIRED("candidates-expired", 409, "계획의 후보가 보존 기간이 지나 지워졌습니다");

    private final String code;
    private final int status;
    private final String title;

    DispatchErrorCode(String code, int status, String title) {
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

    /**
     * 라우트의 주문 가운데 후보가 없는 것이 있다.
     *
     * @param routeId 다시 쓰려던 라우트
     * @param orderId 후보가 없는 주문 하나
     * @return {@link #CANDIDATES_EXPIRED}
     */
    public static DomainException candidatesExpired(UUID routeId, UUID orderId) {
        return new DomainException(CANDIDATES_EXPIRED,
                "후보가 지워진 주문이 있어 라우트를 다시 쓸 수 없습니다 — 계획을 새로 돌려야 합니다",
                Map.of("routeId", routeId.toString(), "orderId", orderId.toString()));
    }
}
