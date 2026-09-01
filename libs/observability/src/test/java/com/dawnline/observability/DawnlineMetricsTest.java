package com.dawnline.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 메트릭 이름 상수가 DESIGN.md §9.1 표와 어긋나지 않는지 지키는 테스트.
 *
 * <p>상수를 두는 목적 자체가 "오타로 인한 메트릭 분열 방지"이므로, 상수 목록이 표와
 * 어긋나는 순간이 곧 회귀다.
 */
class DawnlineMetricsTest {

    /** Prometheus 메트릭 이름 규칙. */
    private static final Pattern PROMETHEUS_METRIC_NAME = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");

    @Test
    void ALL_모든이름_중복이없다() {
        assertThat(DawnlineMetrics.ALL).doesNotHaveDuplicates();
    }

    @Test
    void ALL_설계서9_1표_11개와정확히일치한다() {
        // 이 목록은 docs/DESIGN.md §9.1 표를 그대로 옮긴 것이다. 표가 바뀌면 여기도 바뀐다.
        assertThat(DawnlineMetrics.ALL).containsExactlyInAnyOrder(
                "dawnline_orders_placed_total",
                "dawnline_outbox_lag_seconds",
                "dawnline_outbox_unpublished",
                "dawnline_event_processed_total",
                "dawnline_wave_orders",
                "dawnline_plan_duration_seconds",
                "dawnline_plan_cost_krw",
                "dawnline_plan_unassigned",
                "dawnline_plan_degraded_total",
                "dawnline_delivery_on_time_ratio",
                "dawnline_at_risk_total");
    }

    @Test
    void 선언된상수_ALL목록에빠짐없이들어있다() throws IllegalAccessException {
        // 상수만 추가하고 ALL 갱신을 잊는 사고를 막는다.
        assertThat(declaredMetricNames()).containsExactlyInAnyOrderElementsOf(DawnlineMetrics.ALL);
    }

    @Test
    void 모든이름_dawnline접두사와prometheus명명규칙을만족한다() {
        assertThat(DawnlineMetrics.ALL).allSatisfy(name -> {
            assertThat(name).startsWith("dawnline_");
            assertThat(PROMETHEUS_METRIC_NAME.matcher(name).matches())
                    .as("Prometheus 메트릭 이름 규칙 위반: %s", name)
                    .isTrue();
        });
    }

    /** {@code public static final String} 으로 선언된 메트릭 이름 상수를 리플렉션으로 모은다. */
    private static List<String> declaredMetricNames() throws IllegalAccessException {
        List<String> names = new ArrayList<>();
        for (Field field : DawnlineMetrics.class.getDeclaredFields()) {
            boolean constant = Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers());
            if (constant && field.getType() == String.class) {
                names.add((String) field.get(null));
            }
        }
        return names;
    }
}
