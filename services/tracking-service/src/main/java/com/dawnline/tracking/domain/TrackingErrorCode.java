package com.dawnline.tracking.domain;

import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * tracking-service 고유 오류 코드 (CLAUDE.md 「코딩 컨벤션」 — 서비스 고유 오류는 서비스에서 정의한다).
 *
 * <p>{@code CommonErrorCode} 에 없는 것만 둔다. 여기 있는 코드는 <strong>단말이 코드만 보고 다음 행동을 정할 수 있어야</strong>
 * 의미가 있다.
 */
public enum TrackingErrorCode implements ErrorCode {

    /**
     * 스캔이 같은 배송을 바꾸는 다른 쓰기(개정 반영 · 같은 라우트의 다른 스캔)와 거듭 겹쳐 적용하지 못했다 (DESIGN.md §5.4).
     *
     * <p>{@code conflict} 와 나누는 이유: 그 409 는 「상태 머신이 허용하지 않는 전이」라 다시 보내도 같다. 이것은 <strong>적용되지
     * 않았고, 같은 요청을 그대로 다시 보내면 된다</strong> — 스캔은 상태 머신이 멱등을 만든다. 500 으로 두면 단말은 적용됐는지
     * 모른다(첫 {@code peak-day} 에서 2건).
     */
    SHIPMENT_CONTENDED("shipment-contended", 409, "같은 배송을 바꾸는 다른 쓰기와 겹쳐 스캔을 적용하지 못했습니다");

    private final String code;
    private final int status;
    private final String title;

    TrackingErrorCode(String code, int status, String title) {
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
     * 재시도를 다 쓰고도 겹쳤다.
     *
     * @param routeId  스캔의 라우트(확인용 컨텍스트)
     * @param orderIds 스캔한 주문들 — {@code DEPARTED_CAMP} 는 비어 있다
     * @param attempts 시도한 횟수
     * @param last     마지막 시도의 실패 — 로그가 원인을 잃지 않게
     * @return {@link #SHIPMENT_CONTENDED}
     */
    public static DomainException shipmentContended(UUID routeId, List<UUID> orderIds, int attempts,
            RuntimeException last) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("routeId", routeId.toString());
        details.put("orderIds", orderIds.stream().map(UUID::toString).toList());
        details.put("attempts", attempts);
        return new DomainException(SHIPMENT_CONTENDED,
                "같은 배송을 바꾸는 다른 쓰기와 %d 번 겹쳤습니다 — 적용되지 않았고, 같은 요청을 다시 보내면 됩니다".formatted(attempts),
                details, last);
    }
}
