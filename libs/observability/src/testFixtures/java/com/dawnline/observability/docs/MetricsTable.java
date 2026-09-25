package com.dawnline.observability.docs;

import com.dawnline.observability.DawnlineMetric.Label;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code docs/DESIGN.md} §9.1 의 메트릭 표와 §9.4 의 알림 표를 읽는다 (ADR-060).
 *
 * <p>카탈로그 대조({@code DawnlineMetricsTest}), 대시보드 · 규칙 대조, 서비스 IT 의 「알림 걸린 닫힌 카운터가 기동 때
 * 있다」가 표를 <strong>같은 방식으로</strong> 읽어야 대조가 같은 표를 본다 — 그래서 파서가 테스트 픽스처에 있다
 * ({@code RetentionTable} 과 같은 이유).
 *
 * <p>라벨 칸은 §9.1 「라벨 칸의 문법」대로 읽는다: 괄호 밖의 첫 {@code  — } 까지가 라벨 목록, 쉼표로 가르고,
 * {@code key(v1/v2)} 는 닫힌 라벨, 괄호 없는 {@code key} 는 열린 라벨, {@code 라벨 없음} 은 빈 집합.
 *
 * <p>이 클래스를 쓰는 테스트는 {@code docs/DESIGN.md} 를 태스크 입력으로 선언해야 한다.
 */
public final class MetricsTable {

    /** §9.1 표의 머리 줄. */
    static final String METRICS_HEADER = "| 메트릭 | 타입 | emit 주체 | 라벨 |";

    /** §9.4 알림 표의 머리 줄. */
    static final String ALERTS_HEADER = "| 알림 | 조건 | 뜻 · 볼 곳 |";

    /** 첫 칸 — 백틱으로 감싼 이름. */
    private static final Pattern NAME = Pattern.compile("^`([a-z_:][a-z0-9_:]*)`$");

    /** 라벨 하나 — {@code key} 또는 {@code key(값들)}. */
    private static final Pattern LABEL = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)(?:\\((.*)\\))?$", Pattern.DOTALL);

    /** 라벨이 없다는 말. */
    public static final String NO_LABELS = "라벨 없음";

    private MetricsTable() {
    }

    /**
     * §9.1 의 한 행.
     *
     * @param name   Prometheus 이름
     * @param type   타입 칸 그대로(counter · gauge · histogram)
     * @param emit   emit 주체 칸 그대로
     * @param labels 라벨 칸을 읽은 집합
     */
    public record Row(String name, String type, String emit, List<Label> labels) {
    }

    /**
     * §9.4 알림 표의 한 행.
     *
     * @param alert     알림 이름 — 규칙 파일의 {@code alert:}
     * @param condition 조건 칸 그대로
     */
    public record Alert(String alert, String condition) {
    }

    /**
     * @return §9.1 의 행 전부, 표의 순서대로
     */
    public static List<Row> rows() {
        return parse(read(locateRepoRoot().resolve("docs/DESIGN.md")));
    }

    /**
     * @return §9.4 의 알림 전부, 표의 순서대로
     */
    public static List<Alert> alerts() {
        List<Alert> alerts = new ArrayList<>();
        for (List<String> cells : table(read(locateRepoRoot().resolve("docs/DESIGN.md")), ALERTS_HEADER, 3)) {
            alerts.add(new Alert(name(cells.get(0)), cells.get(1)));
        }
        return List.copyOf(alerts);
    }

    /**
     * 문서에서 §9.1 표를 읽는다. 머리가 없거나 표가 비면 실패한다 — 대조가 공허해지는 것을 초록으로 두지 않는다.
     *
     * @param markdown {@code DESIGN.md} 전체
     * @return 행들
     */
    public static List<Row> parse(String markdown) {
        List<Row> rows = new ArrayList<>();
        for (List<String> cells : table(markdown, METRICS_HEADER, 4)) {
            rows.add(new Row(name(cells.get(0)), cells.get(1), cells.get(2), labels(cells.get(3))));
        }
        return List.copyOf(rows);
    }

    /**
     * 라벨 칸을 읽는다 — §9.1 「라벨 칸의 문법」.
     *
     * @param cell 라벨 칸 그대로
     * @return 라벨들
     */
    public static List<Label> labels(String cell) {
        String plain = cell.replace("**", "").replace("`", "");
        String list = beforeTopLevelDash(plain).strip();
        if (list.equals(NO_LABELS)) {
            return List.of();
        }
        List<Label> labels = new ArrayList<>();
        for (String item : splitTopLevel(list, ',')) {
            Matcher matcher = LABEL.matcher(item.strip());
            if (!matcher.matches()) {
                throw new IllegalStateException("라벨 칸을 읽을 수 없다(§9.1 「라벨 칸의 문법」): \"" + item.strip()
                        + "\" ← " + cell);
            }
            String values = matcher.group(2);
            if (values == null) {
                labels.add(Label.open(matcher.group(1)));
                continue;
            }
            List<String> parsed = new ArrayList<>();
            for (String value : beforeTopLevelDash(values).split("[/·]")) {
                if (!value.isBlank()) {
                    parsed.add(value.strip());
                }
            }
            labels.add(Label.closed(matcher.group(1), parsed.toArray(String[]::new)));
        }
        return List.copyOf(labels);
    }

    private static List<List<String>> table(String markdown, String header, int columns) {
        int start = markdown.indexOf("\n" + header + "\n");
        if (start < 0) {
            throw new IllegalStateException("표의 머리를 찾지 못했다: " + header);
        }
        List<List<String>> rows = new ArrayList<>();
        String[] lines = markdown.substring(start + 1).split("\n", -1);
        for (int i = 2; i < lines.length && lines[i].startsWith("|"); i++) {
            List<String> cells = cells(lines[i]);
            if (cells.size() != columns) {
                throw new IllegalStateException("칸이 " + columns + "개가 아니다: " + lines[i]);
            }
            rows.add(cells);
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("표에 행이 없다: " + header);
        }
        return rows;
    }

    private static String name(String cell) {
        Matcher matcher = NAME.matcher(cell);
        if (!matcher.matches()) {
            throw new IllegalStateException("첫 칸은 `이름` 이다: " + cell);
        }
        return matcher.group(1);
    }

    /** 괄호 밖의 첫 {@code " — "} 앞. 없으면 전부. */
    private static String beforeTopLevelDash(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && text.startsWith(" — ", i)) {
                return text.substring(0, i);
            }
        }
        return text;
    }

    private static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && c == separator) {
                parts.add(text.substring(from, i));
                from = i + 1;
            }
        }
        parts.add(text.substring(from));
        return parts;
    }

    /**
     * 표의 한 줄을 칸으로 가른다. 칸 안의 {@code |} 는 백틱 안에서도 가른다 — 표의 이름 · 조건 칸이 그것을 쓰지 않는다.
     */
    private static List<String> cells(String line) {
        String trimmed = line.strip();
        String inner = trimmed.substring(1, trimmed.endsWith("|") ? trimmed.length() - 1 : trimmed.length());
        List<String> cells = new ArrayList<>();
        for (String cell : inner.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("문서를 읽지 못했습니다: " + file, e);
        }
    }

    /**
     * 작업 디렉터리에서 위로 올라가며 {@code docs/DESIGN.md} 를 찾는다.
     *
     * @return 저장소 루트
     */
    public static Path locateRepoRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("docs").resolve("DESIGN.md"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("저장소 루트(docs/DESIGN.md)를 찾지 못했다: " + current);
    }
}
