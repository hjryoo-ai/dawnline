package com.dawnline.observability.docs;

import com.dawnline.observability.DawnlineMetric;
import com.dawnline.observability.DawnlineMetrics;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code deploy/compose/prometheus/rules/dawnline-alerts.yml} 을 구조로 읽는다 (DESIGN.md §9.4, ADR-060).
 *
 * <p>대조 검사({@code AlertRulesConsistencyTest})와 서비스 IT 의 {@link AlertedCountersContract} 가 같은 방식으로 읽는다.
 */
public final class AlertRules {

    /** 규칙 파일 — 저장소 루트 기준. */
    public static final String RULES = "deploy/compose/prometheus/rules/dawnline-alerts.yml";

    /** PromQL 안의 {@code dawnline_*} 이름. */
    private static final Pattern DAWNLINE_NAME = Pattern.compile("\\bdawnline_[a-z0-9_]+");

    /** 히스토그램이 Prometheus 에서 늘리는 접미사. */
    private static final List<String> HISTOGRAM_SUFFIXES = List.of("_bucket", "_count", "_sum");

    private AlertRules() {
    }

    /**
     * 알림 규칙 하나.
     *
     * @param alert 알림 이름
     * @param expr  식
     */
    public record Rule(String alert, String expr) {
    }

    /**
     * @return 규칙 파일의 알림 전부, 파일의 순서대로
     */
    @SuppressWarnings("unchecked")
    public static List<Rule> rules() {
        Path file = MetricsTable.locateRepoRoot().resolve(RULES);
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = new Yaml().load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("규칙 파일을 읽지 못했다: " + file, e);
        }
        List<Rule> rules = new ArrayList<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) root.get("groups")) {
            for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
                Object alert = rule.get("alert");
                if (alert != null) {
                    rules.add(new Rule(alert.toString(), rule.get("expr").toString()));
                }
            }
        }
        if (rules.isEmpty()) {
            throw new IllegalStateException("규칙 파일에 알림이 없다: " + file);
        }
        return List.copyOf(rules);
    }

    /**
     * 식이 쓰는 {@code dawnline_*} 이름 — 히스토그램의 {@code _bucket} 따위는 표의 이름으로 되돌린다.
     *
     * @param promql 식
     * @return 표의 이름들(등장 순서)
     */
    public static Set<String> dawnlineNames(String promql) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = DAWNLINE_NAME.matcher(promql);
        while (matcher.find()) {
            names.add(tableName(matcher.group()));
        }
        return names;
    }

    /**
     * Prometheus 에 나타나는 이름을 §9.1 의 이름으로 — 히스토그램의 접미사를 뗀다(그 밑이 카탈로그의 히스토그램일 때만).
     *
     * @param exposed 식이나 패널에 적힌 이름
     * @return §9.1 의 이름
     */
    public static String tableName(String exposed) {
        for (String suffix : HISTOGRAM_SUFFIXES) {
            if (exposed.endsWith(suffix)) {
                String base = exposed.substring(0, exposed.length() - suffix.length());
                Optional<DawnlineMetric> metric = catalogued(base);
                if (metric.isPresent() && metric.get().type() == DawnlineMetric.Type.HISTOGRAM) {
                    return base;
                }
            }
        }
        return exposed;
    }

    /**
     * @param name §9.1 의 이름
     * @return 카탈로그 항목
     */
    public static Optional<DawnlineMetric> catalogued(String name) {
        return DawnlineMetrics.ALL.stream().filter(metric -> metric.name().equals(name)).findFirst();
    }
}
