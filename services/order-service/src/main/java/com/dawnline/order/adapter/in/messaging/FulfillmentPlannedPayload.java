package com.dawnline.order.adapter.in.messaging;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code fulfillment.planned.v1} 페이로드 (§4.3).
 *
 * <p>fulfillment-service 의 같은 이름 record 와 <strong>의도적인 중복</strong>이다 — 서비스 간
 * 소스 의존 금지(불변규칙 3)이고, 공유되는 진실은 계약 파일이다.
 *
 * <p>order-service 가 쓰는 것은 {@code outcome}·{@code orderId}·{@code serviceTier}·
 * {@code promisedWindow}·{@code promiseRevised}·{@code reason} 여섯이다. 나머지(FC·캠프·권역·웨이브)는 받지만 저장하지
 * 않는다 — 그 값들의 주인은 fulfillment 이고, 주문 상세에서 보여 줄 필요가 생기면 그때
 * ops-api 의 읽기 모델이 맡는다(§5.5, 불변규칙 12).
 *
 * @param outcome        {@code PLANNED} 또는 {@code UNSERVICEABLE}
 * @param orderId        주문 id
 * @param serviceTier    티어. 개정된 창도 그 티어의 길이 상한을 지켜야 하므로 검증에 쓴다
 * @param promisedWindow 지금 유효한 약속창
 * @param promiseRevised 하류가 약속을 개정했는가 ({@code PLANNED} 에서 필수)
 * @param reason         배차 불가 사유 ({@code UNSERVICEABLE} 에서만)
 */
public record FulfillmentPlannedPayload(
        String outcome,
        UUID orderId,
        String serviceTier,
        Window promisedWindow,
        @Nullable Boolean promiseRevised,
        @Nullable String reason) {

    /** 계획됨. */
    public static final String PLANNED = "PLANNED";

    /** 배차 불가. */
    public static final String UNSERVICEABLE = "UNSERVICEABLE";

    /** 계약의 {@code timeWindow}. */
    public record Window(Instant start, Instant end) {
    }

    /** 개정됐는가. 없으면 거짓이다 — {@code UNSERVICEABLE} 에는 이 필드가 없다. */
    public boolean revised() {
        return Boolean.TRUE.equals(promiseRevised);
    }
}
