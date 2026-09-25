package com.dawnline.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.observability.docs.AlertRules;
import com.dawnline.observability.docs.MetricsTable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대시보드 JSON ↔ {@code docs/DESIGN.md} §9.1 · §9.4 (ADR-060, 7-0 A6).
 *
 * <p>JSON 을 파일째 비교하지 않는다 — Grafana 는 저장할 때 키를 재정렬하고 id 를 붙여 diff 를 뜻 없이 키운다. 파싱해서
 * <strong>메트릭 이름 집합</strong>으로 본다. 커밋된 파일은 프로비저닝 전용이다(UI 에서 저장하지 않는다 —
 * {@code deploy/compose/README.md}).
 *
 * <p>양방향이고 빼는 방식이다: 패널이 쓰는 {@code dawnline_*} 이름은 전부 §9.1 에 있어야 하고, §9.1 의 모든 행은 패널이나
 * 규칙 어딘가에 나와야 한다. 빼는 행은 {@link #NOT_ON_A_PANEL} 에 이유와 함께 적는다 — 지금은 비어 있다.
 */
class DashboardsConsistencyTest {

    private static final Path DASHBOARDS = MetricsTable.locateRepoRoot().resolve("deploy/compose/grafana/dashboards");

    /** 패널에도 규칙에도 없어도 되는 §9.1 행과 그 이유. 비어 있다 — 행을 더할 때 이유 없이 비워 둘 수 없게. */
    private static final Map<String, String> NOT_ON_A_PANEL = Map.of();

    private static final Pattern DASHBOARD_BULLET = Pattern.compile("(?m)^- `([^`]+)`: ");

    private static final Map<String, JsonNode> BOARDS = load();

    @Test
    void 대시보드는_9_4_가_적은_넷이다() {
        String design = read(MetricsTable.locateRepoRoot().resolve("docs/DESIGN.md"));
        String section = design.substring(design.indexOf("### 9.4 "), design.indexOf("### 9.5 "));
        Set<String> documented = new TreeSet<>();
        Matcher matcher = DASHBOARD_BULLET.matcher(section);
        while (matcher.find()) {
            documented.add(matcher.group(1));
        }
        Set<String> titles = BOARDS.values().stream().map(board -> board.get("title").asString())
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(documented).as("전제 — §9.4 의 대시보드 목록을 읽었다").hasSize(4);
        assertThat(titles).isEqualTo(documented);
    }

    @Test
    void 패널이_쓰는_dawnline_이름은_전부_9_1_에_있다() {
        Set<String> table = MetricsTable.rows().stream().map(MetricsTable.Row::name).collect(Collectors.toSet());
        List<String> unknown = new ArrayList<>();
        panelNames().forEach((panel, names) -> names.stream().filter(name -> !table.contains(name))
                .forEach(name -> unknown.add(panel + " → " + name)));

        assertThat(unknown).as("§9.1 에 없는 이름을 그리는 패널 — 오타면 패널이 영원히 비어 있다").isEmpty();
    }

    @Test
    void 표_9_1_의_모든_행은_패널이나_규칙에_나온다() {
        Set<String> drawn = new TreeSet<>();
        panelNames().values().forEach(drawn::addAll);
        AlertRules.rules().forEach(rule -> drawn.addAll(AlertRules.dawnlineNames(rule.expr())));

        Set<String> missing = MetricsTable.rows().stream().map(MetricsTable.Row::name)
                .filter(name -> !drawn.contains(name))
                .filter(name -> !NOT_ON_A_PANEL.containsKey(name))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(drawn).as("전제 — 패널을 읽었다").hasSizeGreaterThan(30);
        assertThat(missing).as("어디에도 그려지지 않는 §9.1 행 — 패널을 더하거나 NOT_ON_A_PANEL 에 이유와 함께 뺀다").isEmpty();
    }

    @Test
    void 뺀_행은_정말로_어디에도_없다() {
        // 제외가 거짓이 되면(누군가 패널을 더했으면) 목록에서 지운다 — 쓰인 제외는 읽힌다(DESIGN.md §13).
        Set<String> drawn = new TreeSet<>();
        panelNames().values().forEach(drawn::addAll);
        assertThat(NOT_ON_A_PANEL.keySet()).doesNotContainAnyElementsOf(drawn);
    }

    @Test
    void 재계획은_쌓아_그리고_경합_창의_셋은_한_패널에_겹친다() {
        // §9.4 의 두 요구 — 합이 트리거 수라는 것이 보여야 하고, 쌍이 갈리는 것이 한 판에 보여야 한다.
        Map<String, Set<String>> panels = panelNames();
        List<JsonNode> replan = panelsReferencing("dawnline_replan_total");
        assertThat(replan).as("재계획 패널").isNotEmpty();
        assertThat(replan).allSatisfy(panel -> assertThat(
                panel.at("/fieldConfig/defaults/custom/stacking/mode").asString()).isEqualTo("normal"));

        assertThat(panels.values()).as("셋이 한 패널에 있다").anySatisfy(names -> assertThat(names).contains(
                "dawnline_status_after_relocate_total", "dawnline_scan_after_relocate_total",
                "dawnline_at_risk_deviation_mismatch_total"));
    }

    @Test
    void 커밋된_JSON_은_프로비저닝_전용의_모양이다() {
        // UI 에서 저장한 JSON 은 숫자 id 와 version 을 단다 — 그 모양이면 누군가 UI 에서 저장해 덮었다.
        BOARDS.forEach((file, board) -> {
            assertThat(board.path("id").isNull() || board.path("id").isMissingNode()).as("%s 의 id", file).isTrue();
            assertThat(board.has("version")).as("%s 의 version", file).isFalse();
            assertThat(board.get("uid").asString()).as("%s 의 uid", file).startsWith("dawnline-");
        });
        List<String> wrongSource = new ArrayList<>();
        allPanels().forEach(panel -> {
            if (!panel.get("type").asString().equals("text") && !panel.get("type").asString().equals("row")
                    && !"dawnline-prometheus".equals(panel.at("/datasource/uid").asString())) {
                wrongSource.add(panel.get("title").asString());
            }
        });
        assertThat(wrongSource).as("프로비저닝된 데이터소스(dawnline-prometheus)를 쓰지 않는 패널").isEmpty();
    }

    /** 패널 제목 → 그 패널의 식이 쓰는 §9.1 이름들. */
    private static Map<String, Set<String>> panelNames() {
        Map<String, Set<String>> names = new TreeMap<>();
        allPanels().forEach(panel -> {
            Set<String> used = new TreeSet<>();
            panel.path("targets").forEach(target -> used.addAll(AlertRules.dawnlineNames(target.path("expr").asString(""))));
            if (!used.isEmpty()) {
                names.put(panel.get("title").asString(), used);
            }
        });
        return names;
    }

    private static List<JsonNode> panelsReferencing(String name) {
        return allPanels().filter(panel -> {
            List<String> exprs = new ArrayList<>();
            panel.path("targets").forEach(target -> exprs.add(target.path("expr").asString("")));
            return exprs.stream().anyMatch(expr -> AlertRules.dawnlineNames(expr).contains(name));
        }).toList();
    }

    private static Stream<JsonNode> allPanels() {
        List<JsonNode> panels = new ArrayList<>();
        BOARDS.values().forEach(board -> board.path("panels").forEach(panel -> {
            panels.add(panel);
            panel.path("panels").forEach(panels::add); // 접힌 row 안의 패널
        }));
        return panels.stream();
    }

    private static Map<String, JsonNode> load() {
        JsonMapper json = JsonMapper.builder().build();
        Map<String, JsonNode> boards = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(DASHBOARDS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                boards.put(file.getFileName().toString(), json.readTree(read(file)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (boards.isEmpty()) {
            throw new IllegalStateException("대시보드 JSON 이 없다: " + DASHBOARDS);
        }
        return boards;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
