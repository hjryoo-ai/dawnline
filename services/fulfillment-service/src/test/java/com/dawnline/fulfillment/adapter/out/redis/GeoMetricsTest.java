package com.dawnline.fulfillment.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** GEO 메트릭 (§9.1). 라벨 키 집합이 결과마다 같은지까지 본다. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GeoMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GeoMetrics metrics = new GeoMetrics(registry);

    @Test
    void 게이지는_인덱스마다_하나이고_덮어쓴다() {
        metrics.indexLoaded("fc", true);
        metrics.indexLoaded("fc", false);
        metrics.indexLoaded("camp", true);

        assertThat(registry.get(GeoMetrics.LOADED_GAUGE).tag("index", "fc").gauge().value()).isZero();
        assertThat(registry.get(GeoMetrics.LOADED_GAUGE).tag("index", "camp").gauge().value()).isEqualTo(1);
        assertThat(registry.find(GeoMetrics.LOADED_GAUGE).gauges()).hasSize(2);
    }

    @Test
    void 두_결과가_같은_라벨_키를_쓴다() {
        // Prometheus 는 같은 이름의 미터가 같은 라벨 키 집합을 갖기를 요구한다. 한쪽만 라벨을
        // 더하면 등록이 실패한다(ADR-022 에서 jar 로 확인한 제약).
        metrics.servedByRedis("fc");
        metrics.servedByFallback("fc");

        assertThat(registry.find(GeoMetrics.LOOKUPS_COUNTER).counters())
                .hasSize(2)
                .allSatisfy(counter -> assertThat(counter.getId().getTags())
                        .extracting(io.micrometer.core.instrument.Tag::getKey)
                        .containsExactlyInAnyOrder("index", "outcome"));
    }

    @Test
    void 폴백은_bypassed_로_센다() {
        // 레이트 리밋의 bypassed 와 같은 어휘다 — "폴백이 돌고 있다" 는 관측되어야 하는 정상이다.
        metrics.servedByFallback("zone");

        assertThat(registry.get(GeoMetrics.LOOKUPS_COUNTER)
                .tag("index", "zone").tag("outcome", "bypassed").counter().count()).isEqualTo(1);
    }
}
