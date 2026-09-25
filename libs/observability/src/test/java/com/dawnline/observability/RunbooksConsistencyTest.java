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
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 런북 ↔ 규칙 파일 ↔ {@code docs/DESIGN.md} §9.5 (7-5).
 *
 * <p>{@code docs/runbooks/README.md} 의 「알림 14 × 대응」 표는 규칙 파일의 알림을 <strong>비추는 목록</strong>이다 — 알림이 늘었는데 행이
 * 없으면 그 알림은 대응 없이 울린다. 그래서 {@link AlertRulesConsistencyTest} 와 같은 방식으로 <strong>빼서</strong> 본다: 양쪽에서
 * 전부 읽고 차집합이 비었는지. 「절차」 칸이 {@code —} 인 행은 그 행이 절차 전부라는 뜻이고, 그 알림에는 {@code runbook} 주석이
 * 없어야 한다 — 칸과 주석이 서로를 설명한다({@code AdrIndexConsistencyTest} 의 「문서」 열과 같은 모양).
 *
 * <p>그리고 모양 하나: <strong>모든 절차의 첫 줄은 「무엇을 먼저 본다」이고 그 자리가 메트릭 · 로그 · SQL 중 무엇인지 말한다.</strong>
 * 장애 중에 읽는 문서라 첫 질문이 흐려지면 문서 전체가 흐려진다.
 */
class RunbooksConsistencyTest {

    private static final Path ROOT = MetricsTable.locateRepoRoot();
    private static final Path RUNBOOKS = ROOT.resolve("docs/runbooks");
    private static final Path INDEX = RUNBOOKS.resolve("README.md");

    private static final String ALERT_TABLE_HEADER = "| 알림 | 먼저 본다 | 갈래 · 대응 | 절차 |";

    /** 표의 「먼저 본다」 칸 — 자리의 종류가 첫 단어다. */
    private static final Pattern FIRST_LOOK_CELL = Pattern.compile("^\\*\\*(메트릭|로그|SQL)\\*\\* ");

    /** 절차의 첫 줄. */
    private static final Pattern FIRST_LOOK_LINE = Pattern.compile("^\\*\\*먼저 본다 — (메트릭|로그|SQL)\\*\\* ");

    /** 「절차」 칸 — {@code —} 이거나 RB 번호(링크일 수 있다). */
    private static final Pattern PROCEDURE_CELL = Pattern.compile("^(?:—|\\[?(RB-\\d{2})\\]?(?:\\([^)]*\\))?)$");

    private static final Pattern RB_FILE = Pattern.compile("^(RB-\\d{2})-.+\\.md$");
    private static final Pattern RB_ID = Pattern.compile("RB-\\d{2}");

    /** 표의 칸 경계 — {@code \|} 는 칸 안의 글자다(GFM). */
    private static final Pattern CELL_BOUNDARY = Pattern.compile("(?<!\\\\)\\|");

    private static final List<AlertRules.Rule> RULES = AlertRules.rules();

    @Test
    void 알림_표와_규칙_파일은_같은_알림_집합이다() {
        Set<String> tabled = new TreeSet<>(alertRows().keySet());
        Set<String> ruled = RULES.stream().map(AlertRules.Rule::alert).collect(Collectors.toCollection(TreeSet::new));

        Set<String> onlyInTable = new TreeSet<>(tabled);
        onlyInTable.removeAll(ruled);
        Set<String> onlyInRules = new TreeSet<>(ruled);
        onlyInRules.removeAll(tabled);

        assertThat(ruled).as("전제 — 규칙 파일을 읽었다").hasSizeGreaterThan(10);
        assertThat(onlyInRules).as("대응 없이 울리는 알림 — docs/runbooks/README.md 1 에 행을 더한다").isEmpty();
        assertThat(onlyInTable).as("규칙 파일에 없는 알림의 행 — 알림이 사라졌으면 행도 지운다").isEmpty();
    }

    @Test
    void 절차_칸은_규칙의_runbook_주석과_같고_그_문서가_있다() {
        Map<String, AlertRules.Rule> rules = RULES.stream()
                .collect(Collectors.toMap(AlertRules.Rule::alert, Function.identity()));
        Set<String> files = runbookFiles().keySet();
        List<String> mismatched = new ArrayList<>();
        alertRows().forEach((alert, cells) -> {
            Matcher procedure = PROCEDURE_CELL.matcher(cells.get(3));
            if (!procedure.matches()) {
                mismatched.add(alert + " — 「절차」 칸은 — 이거나 RB 번호다: " + cells.get(3));
                return;
            }
            String runbook = procedure.group(1);
            AlertRules.Rule rule = rules.get(alert);
            if (rule == null) {
                return; // 집합 차이는 위 테스트가 말한다
            }
            if (runbook == null ? rule.runbook() != null : !runbook.equals(rule.runbook())) {
                mismatched.add(alert + " — 표 " + (runbook == null ? "—" : runbook) + " · 주석 " + rule.runbook());
            }
            if (runbook != null && !files.contains(runbook)) {
                mismatched.add(alert + " — " + runbook + " 문서가 docs/runbooks 에 없다");
            }
        });
        assertThat(mismatched).as("표의 「절차」 칸 ↔ 규칙의 annotations.runbook ↔ 문서").isEmpty();
    }

    @Test
    void 먼저_본다_칸은_메트릭_로그_SQL_중_하나로_시작한다() {
        List<String> vague = alertRows().entrySet().stream()
                .filter(row -> !FIRST_LOOK_CELL.matcher(row.getValue().get(1)).find())
                .map(row -> row.getKey() + " — " + row.getValue().get(1))
                .toList();
        assertThat(vague).as("「먼저 본다」 칸의 첫 단어가 자리의 종류가 아니다").isEmpty();
    }

    @Test
    void 알림_밖_절차의_첫_줄은_먼저_본다이다() {
        Map<String, String> procedures = firstLinesUnder(read(INDEX), "## 2. ");
        assertThat(procedures).as("전제 — 알림 밖 절차를 읽었다").isNotEmpty();
        assertThat(violations(procedures)).as("알림 밖 절차의 첫 줄").isEmpty();
    }

    @Test
    void 모든_RB_문서의_본문은_먼저_본다로_시작한다() {
        Map<String, String> firstLines = new TreeMap<>();
        runbookFiles().forEach((id, file) -> firstLines.put(id, firstBodyLine(read(file))));
        assertThat(firstLines).as("전제 — RB 문서를 읽었다").isNotEmpty();
        assertThat(violations(firstLines)).as("RB 문서의 머리 표 뒤 첫 줄").isEmpty();
    }

    @Test
    void RB_문서와_9_5_의_목록은_같다() {
        String design = read(ROOT.resolve("docs/DESIGN.md"));
        int start = design.indexOf("\n### 9.5 ");
        int end = design.indexOf("\n## ", start + 1);
        assertThat(start).as("§9.5 를 찾지 못했다").isPositive();
        Set<String> listed = new TreeSet<>();
        Matcher matcher = RB_ID.matcher(design.substring(start, end));
        while (matcher.find()) {
            listed.add(matcher.group());
        }
        assertThat(new TreeSet<>(runbookFiles().keySet())).as("docs/runbooks 의 RB 문서 ↔ §9.5 의 목록").isEqualTo(listed);
    }

    // ------------------------------------------------------------------------------------------------------------

    /** 알림 이름 → 칸 넷. 표의 순서대로. */
    private static Map<String, List<String>> alertRows() {
        String markdown = read(INDEX);
        int start = markdown.indexOf("\n" + ALERT_TABLE_HEADER + "\n");
        assertThat(start).as("런북 표의 머리를 찾지 못했다: %s", ALERT_TABLE_HEADER).isPositive();
        Map<String, List<String>> rows = new LinkedHashMap<>();
        String[] lines = markdown.substring(start + 1).split("\n", -1);
        for (int i = 2; i < lines.length && lines[i].startsWith("|"); i++) {
            List<String> cells = cells(lines[i]);
            assertThat(cells).as("칸이 넷이 아니다: %s", lines[i]).hasSize(4);
            String alert = cells.get(0).replace("`", "");
            assertThat(rows.put(alert, cells)).as("같은 알림의 행이 둘이다: %s", alert).isNull();
        }
        return rows;
    }

    private static List<String> cells(String line) {
        String trimmed = line.strip();
        String inner = trimmed.substring(1, trimmed.endsWith("|") ? trimmed.length() - 1 : trimmed.length());
        return Stream.of(CELL_BOUNDARY.split(inner, -1)).map(String::strip).toList();
    }

    /** {@code section} 으로 시작하는 절 안의 {@code ### } 마다 첫 줄(빈 줄 · 목록 밖). */
    private static Map<String, String> firstLinesUnder(String markdown, String section) {
        int start = markdown.indexOf("\n" + section);
        assertThat(start).as("절을 찾지 못했다: %s", section).isPositive();
        int end = markdown.indexOf("\n## ", start + 1);
        String[] lines = markdown.substring(start, end < 0 ? markdown.length() : end).split("\n", -1);
        Map<String, String> firstLines = new LinkedHashMap<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("### ")) {
                firstLines.put(lines[i], nextNonBlank(lines, i + 1));
            }
        }
        return firstLines;
    }

    /** 머리 표(첫 {@code |} 덩어리) 뒤의 첫 줄. */
    private static String firstBodyLine(String markdown) {
        String[] lines = markdown.split("\n", -1);
        int i = 0;
        while (i < lines.length && !lines[i].startsWith("|")) {
            i++;
        }
        while (i < lines.length && lines[i].startsWith("|")) {
            i++;
        }
        return nextNonBlank(lines, i);
    }

    private static String nextNonBlank(String[] lines, int from) {
        for (int i = from; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return lines[i];
            }
        }
        return "";
    }

    private static List<String> violations(Map<String, String> firstLines) {
        return firstLines.entrySet().stream()
                .filter(entry -> !FIRST_LOOK_LINE.matcher(entry.getValue()).find())
                .map(entry -> entry.getKey() + " — 첫 줄: " + entry.getValue())
                .toList();
    }

    /** RB 번호 → 파일. 디렉터리에서 전부 읽는다 — 열거하지 않는다. */
    private static Map<String, Path> runbookFiles() {
        try (Stream<Path> files = Files.list(RUNBOOKS)) {
            Map<String, Path> byId = new TreeMap<>();
            files.forEach(file -> {
                Matcher matcher = RB_FILE.matcher(file.getFileName().toString());
                if (matcher.matches()) {
                    assertThat(byId.put(matcher.group(1), file)).as("같은 번호의 RB 가 둘이다: %s", matcher.group(1))
                            .isNull();
                }
            });
            return byId;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("문서를 읽지 못했다: " + file, e);
        }
    }
}
