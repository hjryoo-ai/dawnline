package com.dawnline.common.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code docs/DESIGN.md} §7.1 의 <strong>보존 표</strong>를 읽는다 (ADR-058 결정 7).
 *
 * <p>보존 기간은 설정({@code @ConfigurationProperties} 의 기본값)에 살고 표는 그것을 비춘다. 서로를 비추는 두
 * 목록이므로 대조한다(CLAUDE.md) — 설정을 가진 모듈마다 {@code RetentionTableDefaultsTest} 가 이 표의 행 중
 * <em>자기 설정 키</em>를 가진 것을 기본값과 대조하고, {@code libs/common} 의 {@code RetentionTableConsistencyTest}
 * 가 모든 행이 어느 대조에 걸려 있는지 본다. 파서가 여기(테스트 픽스처) 있는 이유는 그 여섯 자리가 표를 같은
 * 방식으로 읽어야 하기 때문이다 — 읽는 방식이 갈라지면 대조가 서로 다른 표를 본다.
 *
 * <p>이 클래스를 쓰는 테스트는 {@code docs/DESIGN.md} 를 태스크 입력으로 선언해야 한다 — 아니면 문서만 바꾼
 * 실행에서 Gradle 이 {@code test} 를 건너뛴다(CLAUDE.md 「서로를 비추는 목록」).
 */
public final class RetentionTable {

    /** 표의 머리. §7.1 안에서 이 줄 다음의 첫 표가 보존 표다. */
    public static final String HEADING = "**보존 표**";

    /** 「30일」 같은 기간. */
    private static final Pattern DAYS = Pattern.compile("^(\\d+)일$");

    /** 설정 키 칸 — 백틱 하나로 감싼 키. */
    private static final Pattern KEY = Pattern.compile("^`([a-z0-9.\\-]+)`$");

    /** 첫 칸 — 백틱으로 감싼 표 이름과 선택적인 꼬리(「— 상한」). */
    private static final Pattern TABLE = Pattern.compile("^`([a-z_]+)`(.*)$");

    /** 무기한을 적는 말. */
    public static final String UNBOUNDED = "무기한";

    private RetentionTable() {
    }

    /**
     * 표의 한 행.
     *
     * @param table  표 이름 — 게이지 {@code dawnline_retention_last_success_age_seconds} 의 {@code table} 라벨
     * @param label  첫 칸 그대로(「`shipments` — 상한」)
     * @param period 보존 칸 그대로(「30일」 · 「무기한」)
     * @param key    설정 키. 무기한이면 없다
     */
    public record Row(String table, String label, String period, Optional<String> key) {

        /**
         * 보존 기간.
         *
         * @return 일 단위 기간. 무기한이면 없다
         * @throws IllegalStateException 기간 칸을 읽을 수 없을 때
         */
        public Optional<Duration> duration() {
            if (UNBOUNDED.equals(period)) {
                return Optional.empty();
            }
            Matcher matcher = DAYS.matcher(period);
            if (!matcher.matches()) {
                throw new IllegalStateException("보존 칸을 읽을 수 없다: " + label + " → " + period);
            }
            return Optional.of(Duration.ofDays(Long.parseLong(matcher.group(1))));
        }
    }

    /**
     * 표의 행 전부.
     *
     * @return 표에 적힌 순서대로
     */
    public static List<Row> rows() {
        return parse(read(locateRepoRoot().resolve("docs/DESIGN.md")));
    }

    /**
     * 설정 키가 이 접두사로 시작하는 행 — 한 모듈의 대조 대상이다.
     *
     * @param keyPrefix 모듈의 설정 접두사(「dawnline.tracking.」)
     * @return 그 행들
     */
    public static List<Row> ownedBy(String keyPrefix) {
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        return rows().stream().filter(row -> row.key().map(key -> key.startsWith(keyPrefix)).orElse(false)).toList();
    }

    /**
     * 설정 레코드의 {@link Duration} 칸이 만드는 설정 키 — 보존 전용 레코드의 기간이 <strong>전부</strong> 표에
     * 있는지 모듈 테스트가 빼는 방식으로 보게 한다(새 기간 칸이 대조 밖에 남지 않게).
     *
     * @param recordType 보존 전용 설정 레코드
     * @param prefix     그 레코드의 접두사(「dawnline.tracking.retention」)
     * @return 칸 이름을 kebab-case 로 바꾼 키들
     */
    public static List<String> durationKeys(Class<? extends Record> recordType, String prefix) {
        List<String> keys = new ArrayList<>();
        for (java.lang.reflect.RecordComponent component : recordType.getRecordComponents()) {
            if (component.getType() == Duration.class) {
                keys.add(prefix + "." + component.getName().replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(
                        java.util.Locale.ROOT));
            }
        }
        return List.copyOf(keys);
    }

    /**
     * 문서에서 표를 읽는다. 머리 뒤의 첫 표만 본다 — 머리가 없거나 표가 비면 실패한다(검사가 공허해지는 것을
     * 초록으로 두지 않는다).
     *
     * @param markdown {@code DESIGN.md} 전체
     * @return 행들
     */
    static List<Row> parse(String markdown) {
        int heading = markdown.indexOf("\n" + HEADING + "\n");
        if (heading < 0) {
            throw new IllegalStateException("§7.1 의 「" + HEADING + "」 머리를 찾지 못했다");
        }
        List<Row> rows = new ArrayList<>();
        boolean inTable = false;
        for (String line : markdown.substring(heading + HEADING.length() + 1).split("\n", -1)) {
            if (!line.startsWith("|")) {
                if (inTable) {
                    break;
                }
                continue;
            }
            inTable = true;
            List<String> cells = cells(line);
            if (cells.get(0).equals("표") || cells.get(0).startsWith("---")) {
                continue;
            }
            rows.add(row(cells, line));
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("보존 표에 행이 없다");
        }
        return List.copyOf(rows);
    }

    private static Row row(List<String> cells, String line) {
        if (cells.size() != 6) {
            throw new IllegalStateException("보존 표의 행은 칸이 여섯이다: " + line);
        }
        Matcher table = TABLE.matcher(cells.get(0));
        if (!table.matches()) {
            throw new IllegalStateException("첫 칸은 `표이름` 으로 시작한다: " + line);
        }
        String keyCell = cells.get(4);
        Optional<String> key;
        if (keyCell.equals("—")) {
            key = Optional.empty();
        } else {
            Matcher matcher = KEY.matcher(keyCell);
            if (!matcher.matches()) {
                throw new IllegalStateException("설정 키 칸은 `키` 이거나 — 다: " + line);
            }
            key = Optional.of(matcher.group(1));
        }
        return new Row(table.group(1), cells.get(0), cells.get(1), key);
    }

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
     * 작업 디렉터리에서 위로 올라가며 {@code docs/DESIGN.md} 를 찾는다 — 모듈 테스트의 작업 디렉터리는 그 모듈이다.
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
        throw new IllegalStateException("docs/DESIGN.md 를 찾지 못했습니다. 작업 디렉터리=" + current);
    }
}
