package com.dawnline.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.observability.docs.AlertRules;
import com.dawnline.observability.docs.MetricsTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 규칙 파일 ↔ {@code docs/DESIGN.md} §9.4 알림 표 ↔ §9.1 카탈로그 (ADR-060 결정 3).
 *
 * <p>§9.4 는 알림을 산문으로 적고 있었다 — 규칙 파일과 대조하려면 표여야 해서 표로 바꿨다. 이 테스트가 그 표를 읽고
 * <strong>빼는 방식</strong>으로 본다. 그리고 규칙이 참조하는 카운터마다 카탈로그의 라벨 칸으로 판정한다 — 열렸으면
 * 식이 부재를 다뤄야 하고, 닫혔으면 서비스 IT 가 기동 때 조합 전부를 본다({@code AlertedCountersContract}).
 */
class AlertRulesConsistencyTest {

    private static final List<AlertRules.Rule> RULES = AlertRules.rules();

    @Test
    void 규칙_파일과_9_4_표는_같은_알림_집합이다() {
        Set<String> documented = MetricsTable.alerts().stream().map(MetricsTable.Alert::alert)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> ruled = RULES.stream().map(AlertRules.Rule::alert).collect(Collectors.toCollection(TreeSet::new));

        Set<String> onlyInTable = new TreeSet<>(documented);
        onlyInTable.removeAll(ruled);
        Set<String> onlyInRules = new TreeSet<>(ruled);
        onlyInRules.removeAll(documented);

        assertThat(documented).as("전제 — §9.4 표를 읽었다").hasSizeGreaterThan(10);
        assertThat(onlyInTable).as("§9.4 에만 있는 알림 — 규칙 파일에 더한다").isEmpty();
        assertThat(onlyInRules).as("규칙 파일에만 있는 알림 — §9.4 에 행을 더한다(설계서가 먼저다)").isEmpty();
    }

    @Test
    void 규칙이_쓰는_dawnline_이름은_전부_9_1_에_있다() {
        Set<String> table = MetricsTable.rows().stream().map(MetricsTable.Row::name).collect(Collectors.toSet());
        List<String> unknown = new ArrayList<>();
        for (AlertRules.Rule rule : RULES) {
            for (String name : AlertRules.dawnlineNames(rule.expr())) {
                if (!table.contains(name)) {
                    unknown.add(rule.alert() + " → " + name);
                }
            }
        }
        assertThat(unknown).as("§9.1 에 없는 이름을 쓰는 규칙 — 오타면 알림이 영원히 비어 있다").isEmpty();
    }

    @Test
    void 열린_카운터에_건_알림은_부재를_다루는_식이다() {
        List<String> openAlerted = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (AlertRules.Rule rule : RULES) {
            for (String name : AlertRules.dawnlineNames(rule.expr())) {
                DawnlineMetric metric = AlertRules.catalogued(name).orElse(null);
                if (metric == null || metric.type() != DawnlineMetric.Type.COUNTER || !metric.hasOpenLabel()) {
                    continue;
                }
                openAlerted.add(rule.alert() + " → " + name);
                if (!handlesAbsence(rule.expr(), name)) {
                    violations.add(rule.alert() + " → " + name + " 의 식에 「x unless x offset w」 가 없다");
                }
            }
        }
        // 전제 — 이 검사가 볼 대상이 있다. 없으면 아래 어설션은 아무것도 보지 않는다.
        assertThat(openAlerted).as("전제 — 열린 카운터에 건 알림").isNotEmpty();
        assertThat(violations).as("열린 카운터는 첫 사건에서 1 로 태어나고 increase() 는 그 첫 증가를 못 읽는다 "
                + "(§9.1 「짝」, 근거: 관측(재현됨) — PrometheusRulesIT)").isEmpty();
    }

    @Test
    void 닫힌_카운터에_건_알림은_기동_때_조합이_있는지를_서비스_IT_가_본다() {
        // 대상은 사람이 적지 않는다 — 규칙 · 카탈로그 · §9.1 「emit 주체」에서 뽑는다. 여기서는 그 뽑기가 비지 않는지와
        // 모든 대상이 어느 서비스의 계약에 걸리는지를 본다(emit 칸을 읽지 못하면 실패한다).
        List<String> covered = new ArrayList<>();
        for (String service : MetricsTable.SERVICES) {
            MetricsTable.alertedClosedCountersEmittedBy(service).forEach(metric -> covered.add(metric.name()));
        }
        assertThat(covered).as("서비스 IT 의 AlertedCountersContract 가 볼 카운터")
                .contains("dawnline_rate_limit_decisions_total", "dawnline_internal_token_rejected_total",
                        "dawnline_ops_commands_total");
    }

    /** {@code x … unless x … offset} — 같은 이름이 unless 의 양쪽에 있고 뒤쪽이 offset 을 단다. */
    private static boolean handlesAbsence(String expr, String name) {
        String selector = Pattern.quote(name) + "(\\{[^}]*\\})?";
        return Pattern.compile(selector + "\\s+unless\\s+" + selector + "\\s+offset\\s+\\d+[smhd]").matcher(expr).find();
    }
}
