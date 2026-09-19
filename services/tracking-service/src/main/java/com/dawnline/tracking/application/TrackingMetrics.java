package com.dawnline.tracking.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;

/**
 * tracking 고유 메트릭 (DESIGN.md §9.1).
 *
 * <p>카운터를 <strong>기동 때 등록</strong>한다. 늦게 등록하면 그 사건이 한 번도 없는 동안
 * 시계열이 아예 없고, Prometheus 에서 「0 이다」와 「그런 지표가 없다」가 구분되지 않는다 —
 * 전자는 정상이고 후자는 배포 사고다.
 */
public class TrackingMetrics {

    /** §9.1 — 취소된 배송에 도착해 무시한 기사 스캔. 라벨 없음. */
    public static final String SCAN_AFTER_CANCEL = "dawnline.scan.after.cancel";

    private final Counter scanAfterCancel;

    /**
     * @param registry 미터 레지스트리
     */
    public TrackingMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        this.scanAfterCancel = Counter.builder(SCAN_AFTER_CANCEL)
                .description("CANCELLED 인 shipment 에 도착해 무시한 기사 스캔 (DESIGN.md §5.4). "
                        + "dispatch 의 dawnline_cancel_too_late_total 과 한 쌍이다")
                .register(registry);
    }

    /**
     * 취소 뒤에 온 스캔을 센다 — 무시하되 센다 (§5.4).
     *
     * @param count 이번 스캔에서 그런 주문의 수
     */
    public void countScanAfterCancel(int count) {
        if (count > 0) {
            scanAfterCancel.increment(count);
        }
    }
}
