package com.dawnline.sim.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 부록 A 의 시나리오 표는 {@code scenarios.yml} 을 비춘다 (DESIGN.md 부록 A, IMPLEMENTATION_PLAN 7-4a).
 *
 * <p>진실은 yml 이다 — 표는 그것을 사람이 읽는 꼴로 비춘 목록이고, 비추는 목록은 대조가 없으면 갈라진다(CLAUDE.md 「서로를 비추는
 * 목록에는 대조 검사를 둔다」). 이 표가 처음 대조됐을 때 네 자리가 갈라져 있었다: {@code tiny} · {@code ops-demo} 가 없었고,
 * {@code late-injection} 의 지연·실패가 15% · 3% 였고(yml 은 100% · 5%), 없는 시나리오 넷이 있는 것처럼 적혀 있었다.
 *
 * <p>이름은 <strong>빼는 방식</strong>으로 본다 — 양쪽을 전부 읽고 차집합이 비었는지. 칸은 넷: 주문 수 · 냉장 비율 ·
 * 지연·실패 확률(기사가 없으면 {@code —}) · 창(시작 {@code start-at} 과 주문 수 ÷ 속도로 계산한 끝).
 */
class ScenariosTableTest {

    private static final String HEADER = "| 시나리오 | 주문 | 냉장 비율 | 지연 확률 | 실패 확률 | 창(KST) | 무엇을 보나 |";

    private static final String NONE = "—";

    @Test
    void 이름_집합이_같다() throws IOException {
        Map<String, List<String>> table = table();
        Map<String, SimProperties.Scenario> yml = scenarios();
        assertThat(table).as("전제 — 부록 A 의 표를 읽었다").hasSizeGreaterThan(3).containsKey("smoke");

        TreeSet<String> onlyInTable = new TreeSet<>(table.keySet());
        onlyInTable.removeAll(yml.keySet());
        TreeSet<String> onlyInYml = new TreeSet<>(yml.keySet());
        onlyInYml.removeAll(table.keySet());
        assertThat(onlyInYml).as("부록 A 에 행이 없는 시나리오 — 표에 더한다").isEmpty();
        assertThat(onlyInTable).as("yml 에 없는 시나리오의 행 — 없는 것을 있는 것처럼 적지 않는다").isEmpty();
    }

    @Test
    void 핵심_파라미터가_같다() throws IOException {
        Map<String, SimProperties.Scenario> yml = scenarios();
        List<String> mismatched = new ArrayList<>();
        table().forEach((name, cells) -> {
            SimProperties.Scenario scenario = yml.get(name);
            if (scenario == null) {
                return;                                 // 이름 검사가 말한다
            }
            SimProperties.Scenario.Driver driver = scenario.driver();
            expect(mismatched, name, "주문", cells.get(0), String.valueOf(scenario.orders()));
            expect(mismatched, name, "냉장 비율", cells.get(1), ratio(scenario.coldRatio()));
            expect(mismatched, name, "지연 확률", cells.get(2), driver == null ? NONE : ratio(driver.delayProbability()));
            expect(mismatched, name, "실패 확률", cells.get(3), driver == null ? NONE : ratio(driver.failureProbability()));
            expect(mismatched, name, "창", cells.get(4), window(scenario));
        });
        assertThat(mismatched).as("부록 A ↔ scenarios.yml — 진실은 yml 이다").isEmpty();
    }

    private static void expect(List<String> mismatched, String name, String column, String cell, String actual) {
        String written = normalized(cell);
        if (!written.equals(actual)) {
            mismatched.add("%s 의 %s — 표 %s · yml %s".formatted(name, column, written, actual));
        }
    }

    /** 천 단위 쉼표와 표기는 지우고, 비율은 같은 꼴로. */
    private static String normalized(String cell) {
        String plain = cell.replace("`", "").replace("**", "").replace(",", "").strip();
        if (plain.matches("\\d+\\.\\d+")) {
            return ratio(Double.parseDouble(plain));
        }
        return plain;
    }

    private static String ratio(double value) {
        return Double.toString(value);
    }

    /** {@code HH:mm–HH:mm} — 시작은 start-at, 끝은 주문 수 ÷ 속도만큼 뒤. */
    private static String window(SimProperties.Scenario scenario) {
        LocalTime start = scenario.windowStart();
        if (start == null) {
            return NONE;
        }
        long seconds = Math.round(scenario.orders() / scenario.ratePerSecond());
        return start + "–" + start.plusSeconds(seconds).withSecond(0);
    }

    /** 부록 A 의 표 — 이름 → [주문, 냉장, 지연, 실패, 창]. 머리가 없으면 실패한다(대조가 공허해지지 않게). */
    private static Map<String, List<String>> table() throws IOException {
        String design = Files.readString(repoRoot().resolve("docs/DESIGN.md"), StandardCharsets.UTF_8);
        int start = design.indexOf("\n" + HEADER + "\n");
        assertThat(start).as("부록 A 의 시나리오 표 머리: " + HEADER).isNotNegative();
        Map<String, List<String>> rows = new LinkedHashMap<>();
        String[] lines = design.substring(start + HEADER.length() + 2).split("\n");
        for (int i = 1; i < lines.length && lines[i].startsWith("|"); i++) {  // 0 은 구분선
            String[] cells = lines[i].split("\\|", -1);
            String name = cells[1].replace("`", "").strip();
            rows.put(name, List.of(cells[2].strip(), cells[3].strip(), cells[4].strip(), cells[5].strip(),
                    cells[6].strip()));
        }
        return rows;
    }

    private static Map<String, SimProperties.Scenario> scenarios() throws IOException {
        List<PropertySource<?>> loaded =
                new YamlPropertySourceLoader().load("scenarios", new ClassPathResource("scenarios.yml"));
        MutablePropertySources sources = new MutablePropertySources();
        loaded.forEach(sources::addLast);
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("dawnline.sim", SimProperties.class)
                .orElseThrow(() -> new AssertionError("dawnline.sim 을 바인딩하지 못했다"))
                .scenarios();
    }

    private static Path repoRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("docs").resolve("adr"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("docs/adr 를 찾지 못했습니다. 작업 디렉터리=" + current);
    }
}
