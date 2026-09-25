package com.dawnline.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.observability.DawnlineMetrics;
import com.dawnline.order.application.port.out.RateLimiter;
import com.dawnline.order.domain.ServiceTier;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * order-service 가 내는 라벨 값은 카탈로그(DawnlineMetrics)의 닫힌 라벨 값 목록과 그 값을 만드는 enum 을 대조한다 (ADR-060 결정 2).
 *
 * <p>닫힌 라벨에 목록 밖의 값이 오면 등록 헬퍼가 실패한다 — 운영에서는 그 유스케이스가 예외를 낸다. enum 에 값이 느는
 * 날 그 실패가 운영의 첫 등록이 아니라 여기서 나야 한다.
 */
class MetricLabelValuesTest {

    @Test
    void tier_는_ServiceTier_전부다() {
        var tiers = Arrays.stream(ServiceTier.values()).map(Enum::name).toList();

        assertThat(DawnlineMetrics.ORDERS_PLACED.label("tier").values()).containsExactlyInAnyOrderElementsOf(tiers);
        assertThat(DawnlineMetrics.IDEMPOTENT_REPLAYS.label("tier").values()).containsExactlyInAnyOrderElementsOf(tiers);
    }

    @Test
    void 레이트_리밋_outcome_은_판정_전부다() {
        assertThat(DawnlineMetrics.RATE_LIMIT_DECISIONS.label("outcome").values()).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(RateLimiter.Outcome.values()).map(o -> o.name().toLowerCase(Locale.ROOT)).toList());
    }
}
