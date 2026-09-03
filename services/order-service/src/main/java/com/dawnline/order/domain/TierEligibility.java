package com.dawnline.order.domain;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

/**
 * 주소·시각 기준으로 이 티어를 받을 수 있는지 판정한다 (DESIGN.md §5.1 도메인 서비스).
 *
 * <p>순수 자바다. 권역 조회는 {@link ServiceableZones} 포트로 나가 있어서 이 클래스 자체는
 * DB 도 Spring 도 모른다 (불변규칙 5).
 *
 * <h2>왜 order-service 가 이걸 판단하는가</h2>
 * FC·캠프·웨이브 결정은 fulfillment-service 의 몫이다(§5.2). 그런데 "새벽 배송이 안 되는 지역인데
 * DAWN 으로 접수" 를 받아 두면, 그 주문은 fulfillment 까지 갔다가 {@code unserviceable} 로 되돌아온다.
 * 고객은 접수 성공 응답을 받은 뒤에야 안 된다는 걸 알게 된다. 접수 시점에 아는 것(권역과 컷오프)만
 * 으로 거를 수 있는 것은 여기서 거른다 — 확정 판정이 아니라 <strong>명백한 불가</strong>의 조기 차단이다.
 *
 * <h2>컷오프</h2>
 * §2.2 의 컷오프는 "이 시각을 넘기면 오늘 웨이브에 못 들어간다" 이지 "주문을 못 받는다" 가 아니다.
 * 넘긴 주문은 다음 웨이브로 간다. 그래서 컷오프는 <strong>여기서 거절 사유가 아니고</strong>,
 * 이 클래스의 판정에 쓰지 않는다. 컷오프가 실제로 쓰이는 곳은 두 군데다 —
 * {@link DeliveryPromise} 가 약속창의 시작을 정할 때(§2.2 표), 그리고 fulfillment-service 가
 * 웨이브를 마감할 때(§5.2). 후자가 이 서비스에 없는 이유는 웨이브를 소유하지 않기 때문이다.
 */
public final class TierEligibility {

    /** 서비스 기준 시간대. 컷오프·배송창이 모두 현지 시각으로 정의된다 (§2.2). */
    public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 권역별 지원 티어 조회 포트.
     *
     * <p>기본 구현은 {@code zones} 테이블 조회다(§5.2). Phase 1 시점에는 그 테이블의 주인인
     * fulfillment-service 가 없으므로 스텁이 들어간다.
     */
    @FunctionalInterface
    public interface ServiceableZones {

        /**
         * 이 권역이 지원하는 티어들. 서비스 불가 지역이면 빈 집합.
         *
         * @param geohash5 권역 키 (5자리 geohash)
         */
        Set<ServiceTier> supportedTiers(String geohash5);
    }

    private final ServiceableZones zones;
    private final Clock clock;

    /**
     * @param zones 권역 조회 포트
     * @param clock 시각 출처 (불변규칙 12)
     */
    public TierEligibility(ServiceableZones zones, Clock clock) {
        this.zones = Objects.requireNonNull(zones, "zones");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 이 주소에서 이 티어로 접수할 수 있는가.
     *
     * @param address 배송지
     * @param tier    요청 티어
     */
    public boolean isEligible(DeliveryAddress address, ServiceTier tier) {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(tier, "tier");
        return zones.supportedTiers(address.geohash5()).contains(tier);
    }

    /**
     * 이 주소에서 지금 접수 가능한 티어들. 불가 지역이면 빈 집합.
     *
     * <p>거절 응답에 "그럼 뭐가 되는지" 를 함께 담기 위한 것이다 — 사용자가 다음에 뭘 해야 할지
     * 모르는 422 는 쓸모가 적다.
     *
     * @param address 배송지
     */
    public Set<ServiceTier> eligibleTiers(DeliveryAddress address) {
        Objects.requireNonNull(address, "address");
        return zones.supportedTiers(address.geohash5());
    }

    /** 서비스 기준 시간대의 현재 시각. 컷오프 관련 판단이 생기면 여기서 쓴다. */
    public LocalTime nowInServiceZone() {
        return LocalTime.now(clock.withZone(SERVICE_ZONE));
    }
}
