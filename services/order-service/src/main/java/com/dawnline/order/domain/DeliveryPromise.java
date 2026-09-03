package com.dawnline.order.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * 접수 시각과 티어로 약속 배송창을 정한다 (DESIGN.md §2.2, §5.1).
 *
 * <p>순수 자바다 (불변규칙 5). {@code Clock} 을 들고 있지 않고 접수 시각을 인자로 받는다 —
 * 주문의 약속창은 <em>그 주문이 접수된 시각</em>으로 정해지지, 계산하는 순간의 시각으로 정해지지
 * 않는다. 재계산해도 같은 답이 나와야 한다(불변규칙 12).
 *
 * <h2>왜 order-service 가 계산하는가</h2>
 * {@code order.placed} 가 {@code promisedWindow} 를 필수로 싣고(§4.3), 접수 응답도 고객에게 그 창을
 * 알려 준다. 따라서 창은 접수 시점에 정해져야 하며 그때 존재하는 서비스는 여기뿐이다.
 * 클라이언트가 창을 지정하게 두는 선택지도 있었지만, 배송 SLA 를 호출자가 정하면 그것은 약속이 아니다.
 *
 * <p>이것은 <strong>어느 웨이브에 실릴지</strong>와 다르다. 웨이브 편성·컷오프 판정은
 * fulfillment-service 의 몫이다(§5.2). 여기서 정하는 것은 고객에게 한 약속뿐이고, 둘이 어긋나면
 * 그것은 지연이며 정시율(§8.1)이 그대로 드러낸다.
 *
 * <h2>서머타임</h2>
 * 창의 경계는 <strong>벽시계 시각</strong>이다("익일 00:00–07:00"). 그래서 두 끝을 각각
 * {@link LocalDate}·{@link LocalTime} 으로 만든 뒤 지역 시간대에 붙인다. {@code Asia/Seoul} 은
 * 서머타임이 없어 지금은 차이가 없지만, 시간대를 바꿔 쓸 때 길이가 아니라 벽시계가 유지되는 쪽이 맞다.
 * ({@link PromisedWindow#of} 의 티어별 길이 상한은 그런 시간대에서 넘칠 수 있고, 그때는 접수가
 * 거부되면서 문제가 드러난다 — 조용히 어긋나는 것보다 낫다.)
 */
public final class DeliveryPromise {

    /** SAME_DAY 1차 컷오프 (§2.2). */
    private static final LocalTime SAME_DAY_FIRST_CUTOFF = LocalTime.of(10, 0);

    /** SAME_DAY 2차 컷오프 (§2.2). */
    private static final LocalTime SAME_DAY_SECOND_CUTOFF = LocalTime.of(14, 0);

    /** SAME_DAY 배송창 길이 — "컷오프 + 6시간 이내" (§2.2). */
    private static final int SAME_DAY_WINDOW_HOURS = 6;

    private static final LocalTime DAWN_START = LocalTime.MIDNIGHT;
    private static final LocalTime DAWN_END = LocalTime.of(7, 0);
    private static final LocalTime NEXT_DAY_START = LocalTime.of(8, 0);
    private static final LocalTime NEXT_DAY_END = LocalTime.of(22, 0);

    private final ZoneId zone;

    /**
     * @param zone 컷오프·배송창을 해석할 시간대
     */
    public DeliveryPromise(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /** §2.2 의 기본값 — 서비스 기준 시간대({@link TierEligibility#SERVICE_ZONE}). */
    public static DeliveryPromise standard() {
        return new DeliveryPromise(TierEligibility.SERVICE_ZONE);
    }

    /**
     * 이 티어로 이 시각에 접수하면 언제까지 배달하기로 약속하는가 (§2.2).
     *
     * @param tier     서비스 티어
     * @param placedAt 접수 시각
     */
    public PromisedWindow promiseFor(ServiceTier tier, Instant placedAt) {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(placedAt, "placedAt");

        ZonedDateTime local = placedAt.atZone(zone);
        LocalDate today = local.toLocalDate();

        return switch (tier) {
            // 컷오프가 전일 24:00 이므로 오늘 접수한 것은 모두 내일 새벽이다.
            case DAWN -> window(today.plusDays(1), DAWN_START, today.plusDays(1), DAWN_END, tier);
            case NEXT_DAY -> window(today.plusDays(1), NEXT_DAY_START, today.plusDays(1), NEXT_DAY_END, tier);
            case SAME_DAY -> sameDay(local.toLocalTime(), today, tier);
        };
    }

    /**
     * 컷오프 두 개(10:00·14:00) 중 아직 지나지 않은 첫 번째가 창의 시작이다.
     * 둘 다 지났으면 다음 날 1차 컷오프로 넘어간다 — 거절이 아니라 다음 웨이브다.
     */
    private PromisedWindow sameDay(LocalTime placedLocalTime, LocalDate today, ServiceTier tier) {
        LocalDate startDate;
        LocalTime startTime;
        if (placedLocalTime.isBefore(SAME_DAY_FIRST_CUTOFF)) {
            startDate = today;
            startTime = SAME_DAY_FIRST_CUTOFF;
        } else if (placedLocalTime.isBefore(SAME_DAY_SECOND_CUTOFF)) {
            startDate = today;
            startTime = SAME_DAY_SECOND_CUTOFF;
        } else {
            startDate = today.plusDays(1);
            startTime = SAME_DAY_FIRST_CUTOFF;
        }
        LocalTime endTime = startTime.plusHours(SAME_DAY_WINDOW_HOURS);
        return window(startDate, startTime, startDate, endTime, tier);
    }

    private PromisedWindow window(LocalDate startDate, LocalTime startTime,
            LocalDate endDate, LocalTime endTime, ServiceTier tier) {
        Instant start = startTime.equals(LocalTime.MIDNIGHT)
                // 서머타임 시작으로 00:00 이 존재하지 않는 날이 있다. atStartOfDay 가 그 경우를 다룬다.
                ? startDate.atStartOfDay(zone).toInstant()
                : startDate.atTime(startTime).atZone(zone).toInstant();
        Instant end = endDate.atTime(endTime).atZone(zone).toInstant();
        return PromisedWindow.of(start, end, tier);
    }
}
