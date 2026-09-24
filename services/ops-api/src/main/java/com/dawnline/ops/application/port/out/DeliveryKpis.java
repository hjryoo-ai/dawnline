package com.dawnline.ops.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 배송 축 KPI — {@code kpi_delivery_hourly} 뷰를 캠프별로 더한다 (DESIGN.md §5.5 「KPI — 두 축, 뷰」).
 *
 * <p>게이지와 대시보드가 <strong>같은 뷰</strong>를 읽는다. 모집단(취소 제외, 결과·캠프·두 약속을 아는
 * 주문)과 정시의 뜻은 뷰에 한 번만 적혀 있고, 게이지는 그 행 24개의 합이다 — 두 정의가 갈라질 자리가
 * 없다. 모집단에서 빠진 수({@code outcome_without_promise})도 같은 행에서 함께 읽는다.
 */
public interface DeliveryKpis {

    /**
     * 버킷 {@code [firstBucket, lastBucket]} 를 더한다. 행이 없는 캠프는 결과에 없다.
     *
     * @param firstBucket 첫 버킷(UTC 정시, 포함)
     * @param lastBucket  마지막 버킷(UTC 정시, 포함)
     * @return 캠프별 합과 모집단에서 빠진 수
     */
    DeliveryWindow window(Instant firstBucket, Instant lastBucket);

    /**
     * 한 창의 배송 축.
     *
     * @param camps                 캠프별 합 — 캠프를 아는 행만
     * @param outcomeWithoutPromise 결과는 났는데 약속(또는 캠프)을 아직 몰라 정시율에서 빠진 수. 캠프를
     *                              모르는 행({@code camp_id IS NULL})의 것까지 전부 — 빠진 것은 캠프를
     *                              가리지 않는다
     */
    record DeliveryWindow(List<CampDeliveries> camps, long outcomeWithoutPromise) {
        public DeliveryWindow {
            camps = List.copyOf(camps);
        }
    }

    /**
     * 한 캠프의 배송 축 합.
     *
     * @param campId          캠프
     * @param delivered       완료
     * @param failed          실패 — 분모에 있고 분자에 없다
     * @param onTimePromised  원 약속 기준 정시
     * @param onTimeRevised   개정 약속 기준 정시
     */
    record CampDeliveries(UUID campId, long delivered, long failed, long onTimePromised, long onTimeRevised) {
        public CampDeliveries {
            Objects.requireNonNull(campId, "campId");
        }

        /** 정시율의 분모 — 결과가 난 주문. 실패를 빼면 정시율이 오른다. */
        public long decided() {
            return delivered + failed;
        }
    }
}
