package com.dawnline.ops.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    /** 창의 버킷 수 — 지금 버킷을 포함한다. */
    int BUCKETS = 24;

    /**
     * 현재 버킷 포함 UTC 정시 버킷 {@value #BUCKETS}개 — 「직전 24시간」이 아니다. 게이지와 조회 API 가 이 한 곳에서
     * 창을 얻는다: 같은 사실을 두 경로로 세면 언젠가 갈리고, 갈린 날 어느 쪽을 믿을지 모른다.
     *
     * @param now 기준 시각 (주입된 시계에서, 불변규칙 12)
     * @return 첫 버킷과 마지막 버킷 (둘 다 포함)
     */
    static Buckets currentBuckets(Instant now) {
        Instant last = now.truncatedTo(ChronoUnit.HOURS);
        return new Buckets(last.minus(Duration.ofHours(BUCKETS - 1L)), last);
    }

    /**
     * 버킷 창.
     *
     * @param first 첫 버킷(UTC 정시, 포함)
     * @param last  마지막 버킷(UTC 정시, 포함) — 지금 버킷이라 늘 부분이다
     */
    record Buckets(Instant first, Instant last) {
        public Buckets {
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(last, "last");
        }
    }

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
     * @param revised         완료된 주문 가운데 약속의 끝이 개정된 수(뷰의 `revised`) — 대시보드의 「개정 수」
     */
    record CampDeliveries(UUID campId, long delivered, long failed, long onTimePromised, long onTimeRevised,
            long revised) {
        public CampDeliveries {
            Objects.requireNonNull(campId, "campId");
        }

        /** 정시율의 분모 — 결과가 난 주문. 실패를 빼면 정시율이 오른다. */
        public long decided() {
            return delivered + failed;
        }

        /**
         * 정시율 — {@code 정시 / (완료 + 실패)}. 게이지와 조회 API 가 이 식 하나를 쓴다.
         *
         * @param promised 원 약속 기준이면 {@code true}(SLO), 개정 약속 기준이면 {@code false}(참고값)
         * @return 정시율, 결과가 없으면 {@code NaN} — 0 은 「전부 늦었다」는 주장이다
         */
        public double onTimeRatio(boolean promised) {
            if (decided() == 0) {
                return Double.NaN;
            }
            return (double) (promised ? onTimePromised : onTimeRevised) / decided();
        }
    }
}
