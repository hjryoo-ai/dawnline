package com.dawnline.order.domain;

import com.dawnline.common.TierSchedule;
import com.dawnline.common.TimeWindow;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 접수 시각과 티어로 약속 배송창을 정한다 (DESIGN.md §2.2, §5.1).
 *
 * <p>순수 자바다 (불변규칙 5). {@code Clock} 을 들고 있지 않고 접수 시각을 인자로 받는다 —
 * 주문의 약속창은 <em>그 주문이 접수된 시각</em>으로 정해지지, 계산하는 순간의 시각으로 정해지지
 * 않는다. 재계산해도 같은 답이 나와야 한다(불변규칙 12).
 *
 * <h2>표는 여기 없다 — {@link TierSchedule} 에 위임한다</h2>
 * §2.2 의 컷오프·배송창 표는 {@code libs/common} 의 {@link TierSchedule} 하나뿐이다
 * ([ADR-020](docs/adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md) 후속 정정 2).
 * fulfillment 가 약속 개정 경로에서 <em>다음</em> 컷오프와 그 창을 물어야 하므로 표가 공유되어야
 * 했고, 그렇다면 이쪽도 같은 구현을 써야 <strong>갈라질 수 없다</strong>. 계산이 둘이면 계약
 * 테스트가 "갈라지면 잡는다" 를 해 줄 뿐이고, 그것은 "갈라질 수 없다" 와 다르다.
 *
 * <p>이 클래스에 남는 것은 <em>도메인 타입으로의 변환</em>이다 — {@link TimeWindow} 를
 * {@link PromisedWindow} 로 감싸며 티어별 길이 상한을 검사하고, 두 값을 {@link Promise} 로 묶는다.
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
 * 창의 경계는 <strong>벽시계 시각</strong>이고, 그 해석은 {@link TierSchedule} 이 한다.
 * {@code Asia/Seoul} 은 서머타임이 없어 지금은 차이가 없지만, 시간대를 바꿔 쓸 때 길이가 아니라
 * 벽시계가 유지되는 쪽이 맞다. ({@link PromisedWindow#of} 의 티어별 길이 상한은 그런 시간대에서
 * 넘칠 수 있고, 그때는 접수가 거부되면서 문제가 드러난다 — 조용히 어긋나는 것보다 낫다.)
 */
public final class DeliveryPromise {

    private final TierSchedule schedule;

    /**
     * @param zone 컷오프·배송창을 해석할 시간대
     */
    public DeliveryPromise(ZoneId zone) {
        this.schedule = new TierSchedule(Objects.requireNonNull(zone, "zone"));
    }

    /** §2.2 의 기본값 — 서비스 기준 시간대({@link TierEligibility#SERVICE_ZONE}). */
    public static DeliveryPromise standard() {
        return new DeliveryPromise(TierEligibility.SERVICE_ZONE);
    }

    /**
     * 접수 시점에 정해지는 두 값 — 이 주문이 실릴 웨이브의 컷오프와 고객에게 한 약속.
     *
     * <p>둘을 한 record 로 묶는 이유는 <strong>같은 계산의 두 결과</strong>이기 때문이다. 따로
     * 계산하는 메서드를 두면 티어가 늘거나 §2.2 표가 바뀔 때 한쪽만 고치는 일이 생기고,
     * 그러면 약속한 창과 실제로 실릴 웨이브가 어긋난다.
     *
     * @param cutoffAt 이 주문이 실릴 웨이브의 컷오프 (§2.2). fulfillment-service 의 웨이브 키
     *                 {@code (campId, tier, cutoffAt)} 가 이 값을 쓴다 (§5.2)
     * @param window   고객에게 약속한 배송창
     */
    public record Promise(Instant cutoffAt, PromisedWindow window) {

        public Promise {
            Objects.requireNonNull(cutoffAt, "cutoffAt");
            Objects.requireNonNull(window, "window");
        }
    }

    /**
     * 이 티어로 이 시각에 접수하면 어느 컷오프에 실리고 언제까지 배달하기로 약속하는가 (§2.2).
     *
     * @param tier     서비스 티어
     * @param placedAt 접수 시각
     */
    public Promise promiseFor(ServiceTier tier, Instant placedAt) {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(placedAt, "placedAt");

        Instant cutoffAt = schedule.cutoffFor(tier.name(), placedAt);
        TimeWindow window = schedule.windowFor(tier.name(), cutoffAt);
        return new Promise(cutoffAt, PromisedWindow.of(window.start(), window.end(), tier));
    }
}
