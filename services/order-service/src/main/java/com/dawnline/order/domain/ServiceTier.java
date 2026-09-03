package com.dawnline.order.domain;

import java.time.Duration;

/**
 * 서비스 티어 (DESIGN.md §2.2).
 *
 * <p>티어는 <strong>컷오프와 약속 배송창</strong>을 결정한다. 그 계산 자체는 여기 두지 않는다 —
 * 약속창은 {@link DeliveryPromise} 가 §2.2 표로 만들고({@code PromisedWindow}), 이 티어로 받을 수
 * 있는지는 {@link TierEligibility} 가 본다.
 *
 * <p>어느 웨이브에 실릴지는 여전히 fulfillment-service 의 책임이다(§5.2). order-service 가 정하는
 * 것은 <em>고객에게 한 약속</em>이고, 그 약속을 지킬 웨이브를 고르는 것은 다른 서비스의 일이다.
 *
 * <p>{@code maxWindowLength} 는 그 검증에 쓰는 상한이다. §2.2 표의 배송창을 길이로 옮긴 값이며,
 * 이보다 긴 약속창은 접수 단계에서 거른다.
 */
public enum ServiceTier {

    /** 새벽 배송. 전일 24:00 컷오프, 익일 00:00–07:00 (7시간). */
    DAWN(Duration.ofHours(7)),

    /** 당일 배송. 10:00·14:00 컷오프, 컷오프 + 6시간 이내. */
    SAME_DAY(Duration.ofHours(6)),

    /** 익일 배송. 24:00 컷오프, 익일 08:00–22:00 (14시간). */
    NEXT_DAY(Duration.ofHours(14));

    private final Duration maxWindowLength;

    ServiceTier(Duration maxWindowLength) {
        this.maxWindowLength = maxWindowLength;
    }

    /** 이 티어가 허용하는 약속 배송창의 최대 길이 (§2.2). */
    public Duration maxWindowLength() {
        return maxWindowLength;
    }

    /** 냉장이 기본 전제인 티어인가. 새벽 배송은 신선식품 비중이 높다 (§6.3 룰 카탈로그의 전제). */
    public boolean isColdChainOriented() {
        return this == DAWN;
    }
}
