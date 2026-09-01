package com.dawnline.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 메트릭 라벨 키·값 상수가 DESIGN.md §9.1 과 어긋나지 않는지 지키는 테스트. */
class MetricTagsTest {

    /** Prometheus 라벨 이름 규칙. {@code __} 로 시작하는 이름은 내부 예약이라 따로 막는다. */
    private static final Pattern PROMETHEUS_LABEL_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    @Test
    void ALL_KEYS_중복이없다() {
        assertThat(MetricTags.ALL_KEYS).doesNotHaveDuplicates();
    }

    @Test
    void ALL_KEYS_설계서9_1표의라벨8개와정확히일치한다() {
        assertThat(MetricTags.ALL_KEYS).containsExactlyInAnyOrder(
                "tier", "camp", "service", "consumer", "eventType", "outcome", "strategy", "mode");
    }

    @Test
    void ALL_KEYS_prometheus라벨명명규칙을만족한다() {
        assertThat(MetricTags.ALL_KEYS).allSatisfy(key -> {
            assertThat(PROMETHEUS_LABEL_NAME.matcher(key).matches())
                    .as("Prometheus 라벨 이름 규칙 위반: %s", key)
                    .isTrue();
            assertThat(key).doesNotStartWith("__");
        });
    }

    @Test
    void ALL_OUTCOMES_설계서가정한4가지다() {
        assertThat(MetricTags.ALL_OUTCOMES)
                .doesNotHaveDuplicates()
                .containsExactly("ok", "dup", "rejected", "dlq");
    }
}
