package com.dawnline.common.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 7-0 이월 대조표와 저장소가 서로를 비추는지 확인한다 ({@code docs/IMPLEMENTATION_PLAN.md} 7-0).
 *
 * <h2>왜 이 테스트가 생겼나</h2>
 * Phase 7 은 새 기능이 아니라 <em>이월된 조건이 판정되는 곳</em>이다. 그 조건은 저장소 곳곳에
 * 「Phase 7 에서 본다」로 흩어져 있었고, 2026-09-25 에 전부에서 빼는 방식으로 뽑자 기억으로 적은
 * 열여덟 개 밖에 열 개 넘게 더 나왔다 — 그중 하나(ADR-033 의 「peak 은 아직 안 쟀다」)는 13일 전에
 * 닫혔는데 문장만 남아 있었고, 하나(Phase 3 의 계약 결손)는 반대로 닫힌 것을 열린 것으로 읽게
 * 했다. 표를 한 번 만드는 것으로는 부족하다: 항목이 닫히거나 새로 미뤄질 때마다 표와 원천이
 * 갈라지고, 갈라진 쪽은 빈자리라 보이지 않는다(CLAUDE.md 「서로를 비추는 목록에는 대조 검사를 둔다」).
 *
 * <h2>무엇을 대조하나</h2>
 * <ol>
 *   <li><strong>원천 ① — 「Phase 7」 표기.</strong> 저장소의 모든 텍스트 파일에서 {@link #MENTION}
 *       에 맞는 줄을 세고, 표의 「원천 목록」(파일 → 줄 수)과 같은지 본다. 항목을 닫는 PR 은 그
 *       문장을 고치므로 줄 수가 바뀌고, 표를 같이 고쳐야 초록이 된다 — 그것이 이 검사의 목적이다.</li>
 *   <li><strong>원천 ② — ADR 의 재검토 지점.</strong> 본문에 「재검토」가 있는 ADR 은 7-0 절 어딘가에
 *       {@code ADR-NNN} 으로 나와야 한다 — A·B·D 로 가져갔든 C 에서 이유와 함께 뺐든. 새 ADR 이
 *       재검토 지점을 가지면 아무것도 고치지 않아도 검사 대상이 된다(빼는 방식, §13 규칙 2).</li>
 *   <li><strong>원천 ③ — Phase 대조표의 열린 표기.</strong> 머리에 「상태」 칸이 있는 표에서 그 칸이
 *       ⚠️ · ◐ · ⏸ 를 들고 있으면, 닫힘(✅ · ⛔ · ❌)으로 시작하거나 {@code → 7-0 A9} 처럼 7-0 의 행을
 *       가리켜야 한다. 다른 Phase 에서 닫히면서 원래 칸이 갱신되지 않은 행이 이렇게 잡힌다 — 처음 판의
 *       D6(Phase 3 의 계약 결손)이 그 모양이었고, 이 검사를 처음 돌리자 둘이 더 나왔다. ⬜(미구현)를 빼는
 *       이유는 계획서의 7-0 절에 있다.</li>
 *   <li>원천 목록의 「행」 칸과 대조표의 {@code → 7-0} 이 가리키는 행(A·B·D 번호)이 표에 실제로 있다.</li>
 * </ol>
 *
 * <h2>빼는 것 셋 — 그리고 각각을 검사가 말한다</h2>
 * 계획서의 「Phase 7」 절(표가 사는 자리), 이 소스 자신(규칙을 설명하느라 그 말을 쓴다), 빌드 산출물 ·
 * 숨은 디렉터리({@code .github} 는 읽는다) · 로컬 전용 {@code .env}. 앞의 둘은 전제 어설션이
 * 「그 자리가 실제로 있다」를 확인한다 — 절 제목이 바뀌어 아무것도 빼지 못하면 조용히 넓어지지
 * 않고 빨갛다.
 */
@DisplayName("Phase 7-0 이월 대조표 — 원천 목록 · 재검토 지점이 있는 ADR 은 저장소와 같다")
class CarryOverLedgerConsistencyTest {

    /** 원천 ① 의 표기. 「Phase7」 처럼 붙여 쓴 것도 잡는다. */
    private static final Pattern MENTION = Pattern.compile("Phase ?7");

    /** 계획서에서 이 표가 사는 절의 제목. 이 절의 줄은 원천이 아니다. */
    private static final String PHASE_SECTION = "## Phase 7 —";

    /** 7-0 절의 처음과 끝. */
    private static final String CARRY_OVER_START = "### 7-0 이월 대조표";
    private static final String CARRY_OVER_END = "\n**작업**";

    /** 원천 목록의 행: {@code | `path` | 12 | A1 · C |}. */
    private static final Pattern SOURCE_ROW =
            Pattern.compile("^\\|\\s*`([^`]+)`\\s*\\|\\s*(\\d+)\\s*\\|(.*)\\|\\s*$");

    /** A·B·D 표의 행 번호: {@code | A12 | …}. */
    private static final Pattern ITEM_ROW = Pattern.compile("^\\|\\s*([ABD]\\d+)\\s*\\|");

    /** 「행」 칸의 토큰 — 행 번호 또는 표 C. */
    private static final Pattern ITEM_REF = Pattern.compile("[ABD]\\d+|C");

    /** 대조표의 열린 표기. ⚠️ 는 변형 선택자가 붙기도 해서 앞 글자만 본다. */
    private static final List<String> OPEN_MARKS = List.of("⚠", "◐", "⏸");

    /** 상태 칸이 이것으로 시작하면 닫힌 행이다 — 뒤에 「이 표를 쓴 시점에는 ⚠️ …」가 남아 있어도. */
    private static final List<String> CLOSED_MARKS = List.of("✅", "⛔", "❌");

    /** 열린 칸이 7-0 의 행을 가리키는 모양. */
    private static final Pattern POINTER = Pattern.compile("→ 7-0 ([ABD]\\d+|C)");

    private static final Pattern ADR_FILE = Pattern.compile("^ADR-(\\d{3})-.*\\.md$");

    /** 이름이 이것이면 들어가지 않는다 — 산출물과 의존성이다. */
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("build", "node_modules", "dist");

    /** 숨은 디렉터리 중 읽는 것. CI 설정이 원천이다. */
    private static final String READ_HIDDEN_DIRECTORY = ".github";

    /** 로컬 전용 파일 — 커밋되지 않는다(CLAUDE.md). */
    private static final String LOCAL_ONLY_FILE = ".env";

    private static final Path REPO_ROOT = locateRepoRoot();
    private static final Path PLAN = REPO_ROOT.resolve("docs/IMPLEMENTATION_PLAN.md");
    /** 원천 ③ 의 두 자리 — 계획서(표의 절 밖)와 Phase 4 의 DoD 대조가 사는 리포트 §8. */
    private static final Path PHASE4_REPORT = REPO_ROOT.resolve("docs/benchmarks/phase4-strategies.md");
    private static final Path SELF = REPO_ROOT.resolve(
            "libs/common/src/test/java/com/dawnline/common/docs/CarryOverLedgerConsistencyTest.java");

    private static final String PLAN_TEXT = read(PLAN);
    private static final String CARRY_OVER = section(PLAN_TEXT, CARRY_OVER_START, CARRY_OVER_END);

    @Test
    void 원천_목록의_줄_수가_저장소와_같다() {
        Map<String, Integer> listed = listedSources();
        Map<String, Integer> found = scanMentions();

        assertThat(listed).as("7-0 절의 원천 목록을 하나도 읽지 못했다 — 표 모양이 바뀌었다").isNotEmpty();
        assertThat(found).as("저장소에서 표기를 하나도 찾지 못했다 — 루트 해석이 깨졌다 (%s)", REPO_ROOT)
                .isNotEmpty();
        assertThat(Files.isRegularFile(SELF))
                .as("빼기로 한 이 소스의 경로가 틀렸다 — 빼지 못하면 이 소스의 줄이 원천으로 세어진다: %s", SELF)
                .isTrue();

        Set<String> unlisted = new TreeSet<>(found.keySet());
        unlisted.removeAll(listed.keySet());
        assertThat(unlisted)
                .as("표기가 있는데 원천 목록에 없는 파일 — 새로 Phase 7 로 미룬 것이 표에 오지 않았다. "
                        + "7-0 표에 행을 더하고(A·B) 원천 목록에 이 파일을 적는다")
                .isEmpty();

        Map<String, String> drifted = new TreeMap<>();
        listed.forEach((file, count) -> {
            int actual = found.getOrDefault(file, 0);
            if (actual != count) {
                drifted.put(file, "목록 " + count + " · 실제 " + actual);
            }
        });
        assertThat(drifted)
                .as("원천 목록과 줄 수가 다른 파일 — 항목을 닫았거나 새로 미뤘다면 7-0 표의 그 행과 "
                        + "원천 목록을 함께 고친다 (실제 0 이면 그 파일의 표기가 전부 사라진 것이다)")
                .isEmpty();
    }

    @Test
    void 재검토_지점이_있는_ADR은_7_0_절에_나온다() {
        Map<Integer, String> reviewed = adrsWithReviewPoints();
        assertThat(reviewed).as("「재검토」가 있는 ADR 을 하나도 찾지 못했다 — 루트 해석이나 읽기가 깨졌다")
                .isNotEmpty();

        List<String> absent = new ArrayList<>();
        reviewed.forEach((number, file) -> {
            if (!CARRY_OVER.contains("ADR-%03d".formatted(number))) {
                absent.add(file);
            }
        });
        assertThat(absent)
                .as("재검토 지점이 있는데 7-0 절 어디에도 없는 ADR — Phase 7 로 가져가거나(A·B) "
                        + "표 C 에 이유와 함께 뺀다. 번호는 `ADR-NNN` 으로 적는다")
                .isEmpty();
    }

    @Test
    void 원천_목록의_행_칸은_있는_행을_가리킨다() {
        Set<String> items = itemIds();

        Map<String, List<String>> dangling = new TreeMap<>();
        referencesBySource().forEach((file, references) -> {
            assertThat(references).as("원천 목록의 `%s` 행은 가리키는 행이 없다", file).isNotEmpty();
            for (String reference : references) {
                if (!items.contains(reference)) {
                    dangling.computeIfAbsent(file, key -> new ArrayList<>()).add(reference);
                }
            }
        });
        assertThat(dangling).as("원천 목록이 가리키는데 표에 없는 행 번호").isEmpty();
    }

    @Test
    void 대조표의_열린_표기는_닫히거나_7_0_의_행을_가리킨다() {
        Set<String> items = itemIds();
        Map<String, List<String>> sources = Map.of(
                "docs/IMPLEMENTATION_PLAN.md", statusCells(withoutPhaseSection(PLAN_TEXT)),
                "docs/benchmarks/phase4-strategies.md", statusCells(read(PHASE4_REPORT)));
        sources.forEach((file, cells) -> assertThat(cells)
                .as("`%s` 에서 「상태」 칸이 있는 표를 하나도 읽지 못했다 — 표 모양이 바뀌었다", file)
                .isNotEmpty());

        List<String> unexplained = new ArrayList<>();
        sources.forEach((file, cells) -> {
            for (String cell : cells) {
                if (OPEN_MARKS.stream().noneMatch(cell::contains)
                        || CLOSED_MARKS.stream().anyMatch(cell::startsWith)) {
                    continue;
                }
                Matcher pointer = POINTER.matcher(cell);
                if (!pointer.find()) {
                    unexplained.add(file + " — 가리키는 곳 없음: " + cell);
                } else if (!items.contains(pointer.group(1))) {
                    unexplained.add(file + " — 없는 행 " + pointer.group(1) + ": " + cell);
                }
            }
        });
        assertThat(unexplained)
                .as("열린 표기(⚠️ ◐ ⏸)인데 닫힘으로 바뀌지도, 7-0 의 행을 가리키지도 않는 상태 칸. "
                        + "다른 Phase 에서 닫혔으면 `✅ 닫힘 — 어디서(이 표를 쓴 시점에는 …)` 로, 아직 열려 있으면 "
                        + "7-0 표에 행을 두고 `→ 7-0 A9` 처럼 가리킨다")
                .isEmpty();
    }

    // --- 표 읽기 ---------------------------------------------------------------

    /** 7-0 의 A·B·D 행 번호와 표 C. 같은 번호가 두 번이면 던진다. */
    private static Set<String> itemIds() {
        Set<String> items = new TreeSet<>();
        for (String line : CARRY_OVER.split("\n", -1)) {
            Matcher matcher = ITEM_ROW.matcher(line);
            if (matcher.find() && !items.add(matcher.group(1))) {
                throw new AssertionError("7-0 표에 같은 번호가 두 번 있다: " + matcher.group(1));
            }
        }
        assertThat(items).as("7-0 의 A·B·D 표를 읽지 못했다").isNotEmpty();
        items.add("C");
        return items;
    }

    /** 머리에 「상태」 칸이 있는 표들의 그 칸 — 구분 행은 뺀다. */
    private static List<String> statusCells(String markdown) {
        List<String> cells = new ArrayList<>();
        int column = -1;
        boolean inTable = false;
        for (String line : markdown.split("\n", -1)) {
            if (!line.startsWith("|")) {
                inTable = false;
                continue;
            }
            String[] row = line.strip().replaceAll("^\\||\\|$", "").split("\\|", -1);
            if (!inTable) {
                inTable = true;
                column = -1;
                for (int i = 0; i < row.length; i++) {
                    if (row[i].strip().equals("상태")) {
                        column = i;
                    }
                }
                continue;
            }
            if (column < 0 || column >= row.length || row[column].strip().matches("[-: ]+")) {
                continue;
            }
            cells.add(row[column].strip());
        }
        return cells;
    }

    private static Map<String, Integer> listedSources() {
        Map<String, Integer> listed = new TreeMap<>();
        for (String line : CARRY_OVER.split("\n", -1)) {
            Matcher matcher = SOURCE_ROW.matcher(line);
            if (matcher.matches() && listed.put(matcher.group(1), Integer.parseInt(matcher.group(2))) != null) {
                throw new AssertionError("원천 목록에 같은 파일이 두 번 있다: " + matcher.group(1));
            }
        }
        return listed;
    }

    private static Map<String, List<String>> referencesBySource() {
        Map<String, List<String>> references = new TreeMap<>();
        for (String line : CARRY_OVER.split("\n", -1)) {
            Matcher matcher = SOURCE_ROW.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            List<String> tokens = new ArrayList<>();
            Matcher ref = ITEM_REF.matcher(matcher.group(3));
            while (ref.find()) {
                tokens.add(ref.group());
            }
            references.put(matcher.group(1), tokens);
        }
        return references;
    }

    // --- 저장소 읽기 -----------------------------------------------------------

    /** 파일(루트 기준 {@code /} 경로) → 표기가 있는 줄 수. 0 인 파일은 넣지 않는다. */
    private static Map<String, Integer> scanMentions() {
        Map<String, Integer> found = new TreeMap<>();
        try {
            Files.walkFileTree(REPO_ROOT, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(REPO_ROOT)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName().toString();
                    boolean hidden = name.startsWith(".") && !name.equals(READ_HIDDEN_DIRECTORY);
                    return hidden || SKIPPED_DIRECTORIES.contains(name)
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().equals(LOCAL_ONLY_FILE) || file.equals(SELF)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String text = readTextOrNull(file);
                    if (text == null) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (file.equals(PLAN)) {
                        text = withoutPhaseSection(text);
                    }
                    int count = countMentions(text);
                    if (count > 0) {
                        found.put(REPO_ROOT.relativize(file).toString().replace('\\', '/'), count);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("저장소를 읽지 못했습니다: " + REPO_ROOT, e);
        }
        return found;
    }

    /** 계획서에서 표가 사는 절을 뺀다. 절을 못 찾으면 빼지 못한 채 넓어지지 않도록 던진다. */
    private static String withoutPhaseSection(String plan) {
        int start = plan.indexOf("\n" + PHASE_SECTION);
        if (start < 0) {
            throw new IllegalStateException("계획서에서 표의 절을 찾지 못했습니다: " + PHASE_SECTION);
        }
        int end = plan.indexOf("\n## ", start + 1);
        if (end < 0) {
            throw new IllegalStateException("표의 절 다음 제목을 찾지 못했습니다");
        }
        return plan.substring(0, start) + plan.substring(end);
    }

    private static int countMentions(String text) {
        int count = 0;
        for (String line : text.split("\n", -1)) {
            if (MENTION.matcher(line).find()) {
                count++;
            }
        }
        return count;
    }

    /** 본문에 「재검토」가 있는 ADR — 번호 → 파일명. */
    private static Map<Integer, String> adrsWithReviewPoints() {
        Map<Integer, String> found = new TreeMap<>();
        try (Stream<Path> entries = Files.list(REPO_ROOT.resolve("docs/adr"))) {
            entries.forEach(entry -> {
                Matcher matcher = ADR_FILE.matcher(entry.getFileName().toString());
                if (matcher.matches() && read(entry).contains("재검토")) {
                    found.put(Integer.parseInt(matcher.group(1)), entry.getFileName().toString());
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("docs/adr 를 읽지 못했습니다", e);
        }
        return found;
    }

    /** UTF-8 텍스트가 아니면(NUL 이 있거나 디코딩이 실패하면) {@code null}. */
    private static String readTextOrNull(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            for (byte b : bytes) {
                if (b == 0) {
                    return null;
                }
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("파일을 읽지 못했습니다: " + file, e);
        }
    }

    private static String section(String markdown, String heading, String end) {
        int start = markdown.indexOf(heading);
        if (start < 0) {
            throw new IllegalStateException("제목을 찾지 못했습니다: " + heading);
        }
        int stop = markdown.indexOf(end, start);
        if (stop < 0) {
            throw new IllegalStateException("절의 끝을 찾지 못했습니다: " + end);
        }
        return markdown.substring(start, stop);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("문서를 읽지 못했습니다: " + file, e);
        }
    }

    /** {@code AdrIndexConsistencyTest} 와 같은 방식 — 작업 디렉터리에서 위로 올라가며 찾는다. */
    private static Path locateRepoRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("docs").resolve("IMPLEMENTATION_PLAN.md"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("docs/IMPLEMENTATION_PLAN.md 를 찾지 못했습니다. 작업 디렉터리=" + current);
    }
}
