package com.dawnline.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.fulfillment.domain.FcFallbackReason;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.WaveCloseCause;
import com.dawnline.observability.DawnlineMetrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * fulfillment 가 내는 라벨 값은 카탈로그(DawnlineMetrics)의 닫힌 라벨 값 목록과 그 값을 만드는 enum 을 대조한다 (ADR-060 결정 2).
 *
 * <p>닫힌 라벨에 목록 밖의 값이 오면 등록 헬퍼가 실패한다 — 운영에서는 그 유스케이스가 예외를 낸다. enum 에 값이 느는
 * 날 그 실패가 운영의 첫 등록이 아니라 여기서 나야 한다.
 */
class MetricLabelValuesTest {

    @Test
    void tier_는_ServiceTier_전부다() {
        List<String> tiers = Arrays.stream(ServiceTier.values()).map(Enum::name).toList();

        assertThat(DawnlineMetrics.WAVE_ORDERS.label("tier").values()).containsExactlyInAnyOrderElementsOf(tiers);
        assertThat(DawnlineMetrics.PROMISE_REVISED.label("tier").values()).containsExactlyInAnyOrderElementsOf(tiers);
    }

    @Test
    void 대체_FC_reason_은_필터_전부다() {
        assertThat(DawnlineMetrics.FC_FALLBACK.label("reason").values()).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(FcFallbackReason.values()).map(r -> r.name().toLowerCase(Locale.ROOT)).toList());
    }

    @Test
    void 개정_cause_는_마감_원인_전부와_unknown_이다() {
        // unknown 은 불변식이 깨졌을 때의 값이다(FulfillmentMetrics.CAUSE_UNKNOWN) — 목록에서 빼면 그 순간 개정을 세다
        // 예외가 난다. 개정은 세야 하므로 값으로 남긴다(§9.1).
        List<String> causes = new ArrayList<>(
                Arrays.stream(WaveCloseCause.values()).map(c -> c.name().toLowerCase(Locale.ROOT)).toList());
        causes.add(FulfillmentMetrics.CAUSE_UNKNOWN);

        assertThat(DawnlineMetrics.PROMISE_REVISED.label("cause").values()).containsExactlyInAnyOrderElementsOf(causes);
    }
}
