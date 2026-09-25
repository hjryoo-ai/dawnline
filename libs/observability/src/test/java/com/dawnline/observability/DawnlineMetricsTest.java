package com.dawnline.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.observability.DawnlineMetric.Label;
import com.dawnline.observability.docs.MetricsTable;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 카탈로그 ↔ {@code docs/DESIGN.md} §9.1 — 서로를 비추는 두 목록의 대조 (ADR-060 결정 1, 7-0 A30).
 *
 * <p>이 테스트의 앞 판은 「§9.1 표를 그대로 옮긴 것」이라고 적힌 11개 목록과 카탈로그를 비교했고 <strong>문서를 읽지
 * 않았다</strong> — 표가 39행으로 자라는 동안 초록이었다. 이제 표를 읽고 <strong>빼는 방식</strong>으로 본다: 표에만 있는
 * 이름과 카탈로그에만 있는 이름이 둘 다 빈 집합이어야 한다. {@code docs/DESIGN.md} 는 {@code test} 의 입력이다.
 */
class DawnlineMetricsTest {

    private static final List<MetricsTable.Row> TABLE = MetricsTable.rows();

    private static final Map<String, DawnlineMetric> CATALOGUE = DawnlineMetrics.ALL.stream()
            .collect(Collectors.toMap(DawnlineMetric::name, Function.identity(), (a, b) -> a, LinkedHashMap::new));

    @Test
    void 표를_읽었다() {
        // 대조의 전제 — 파서가 빈 표를 돌려주면 아래 검사는 전부 공허하게 통과한다.
        assertThat(TABLE).as("§9.1 표의 행").hasSizeGreaterThan(30);
    }

    @Test
    void 표와_카탈로그는_같은_이름_집합이다() {
        Set<String> documented = TABLE.stream().map(MetricsTable.Row::name).collect(Collectors.toCollection(TreeSet::new));

        Set<String> onlyInTable = new TreeSet<>(documented);
        onlyInTable.removeAll(CATALOGUE.keySet());
        Set<String> onlyInCatalogue = new TreeSet<>(CATALOGUE.keySet());
        onlyInCatalogue.removeAll(documented);

        assertThat(onlyInTable).as("§9.1 에만 있는 이름 — 카탈로그(DawnlineMetrics)에 항목을 더한다").isEmpty();
        assertThat(onlyInCatalogue).as("카탈로그에만 있는 이름 — §9.1 에 행을 더한다(설계서가 먼저다)").isEmpty();
    }

    @Test
    void 표에_같은_이름이_두_번_나오지_않는다() {
        assertThat(TABLE.stream().map(MetricsTable.Row::name).toList()).doesNotHaveDuplicates();
        assertThat(DawnlineMetrics.ALL.stream().map(DawnlineMetric::name).toList()).doesNotHaveDuplicates();
    }

    @Test
    void 행마다_타입이_같다() {
        List<String> mismatched = new ArrayList<>();
        for (MetricsTable.Row row : TABLE) {
            DawnlineMetric metric = CATALOGUE.get(row.name());
            if (metric != null && !metric.type().cell().equals(row.type())) {
                mismatched.add(row.name() + ": 표 " + row.type() + " · 카탈로그 " + metric.type().cell());
            }
        }
        assertThat(mismatched).as("타입 칸이 다른 행").isEmpty();
    }

    @Test
    void 행마다_라벨_집합이_같다_닫힘과_값까지() {
        List<String> mismatched = new ArrayList<>();
        for (MetricsTable.Row row : TABLE) {
            DawnlineMetric metric = CATALOGUE.get(row.name());
            if (metric != null && !describe(metric.labels()).equals(describe(row.labels()))) {
                mismatched.add(row.name() + "\n    표      " + describe(row.labels())
                        + "\n    카탈로그 " + describe(metric.labels()));
            }
        }
        assertThat(mismatched).as("라벨 칸이 다른 행 — 열린 라벨은 key, 닫힌 라벨은 key(값/값)").isEmpty();
    }

    @Test
    void Micrometer_이름은_Prometheus_에서_표의_이름이_된다() {
        // 이름 대응은 주석이 아니라 레지스트리가 말한다 — 항목마다 실제로 등록해 긁어 본다.
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        for (DawnlineMetric metric : DawnlineMetrics.ALL) {
            String[] tags = sampleTags(metric);
            switch (metric.type()) {
                case COUNTER -> DawnlineMeters.counter(registry, metric, tags);
                case HISTOGRAM -> DawnlineMeters.timer(registry, metric, tags);
                case GAUGE -> DawnlineMeters.gauge(registry, metric, new Object(), state -> 1.0, tags);
            }
        }
        String scrape = registry.scrape();

        List<String> missing = new ArrayList<>();
        for (DawnlineMetric metric : DawnlineMetrics.ALL) {
            String sample = metric.type() == DawnlineMetric.Type.HISTOGRAM ? metric.name() + "_bucket{" : metric.name() + "{";
            String bare = metric.name() + " ";
            if (!scrape.contains("\n" + sample) && !scrape.contains("\n" + bare)) {
                missing.add(metric.meterName() + " → " + sample);
            }
        }
        assertThat(missing).as("Micrometer 이름이 표의 Prometheus 이름으로 나오지 않는 항목").isEmpty();
    }

    @Test
    void 선언된_항목이_ALL_에_빠짐없이_들어_있다() throws IllegalAccessException {
        // 상수만 더하고 ALL 을 잊으면 위의 대조가 그 항목을 보지 못한다.
        List<DawnlineMetric> declared = new ArrayList<>();
        for (Field field : DawnlineMetrics.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && field.getType() == DawnlineMetric.class) {
                declared.add((DawnlineMetric) field.get(null));
            }
        }
        assertThat(declared).containsExactlyInAnyOrderElementsOf(DawnlineMetrics.ALL);
    }

    /** 닫힌 라벨은 첫 값, 열린 라벨은 아무 값. */
    private static String[] sampleTags(DawnlineMetric metric) {
        List<String> tags = new ArrayList<>();
        for (Label label : metric.labels()) {
            tags.add(label.key());
            tags.add(label.closed() ? label.values().getFirst() : "x");
        }
        return tags.toArray(String[]::new);
    }

    private static String describe(List<Label> labels) {
        return labels.stream()
                .map(label -> label.closed() ? label.key() + "(" + String.join("/", label.values()) + ")" : label.key())
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
