package com.dawnline.common.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * ADR 번호는 세 곳에 적혀 있다 — {@code docs/adr/ADR-*.md} 파일, {@code docs/DESIGN.md} §16 표,
 * {@code docs/adr/README.md} 표. 셋이 같은 집합인지 확인한다 (DESIGN.md §13 규칙 3).
 *
 * <h2>왜 이 테스트가 생겼나</h2>
 * 2026-09-19 에 {@code docs/adr/README.md} 의 표에 <strong>039–044 여섯 줄이 없었다</strong>.
 * 파일도 §16 표도 멀쩡했고 README 만 비어 있었다. 그 표는 스스로 「§16 과 같은 내용이며 함께
 * 갱신한다」고 적어 두므로, 빈 줄은 「아직 없는 ADR」로 읽힐 수밖에 없다 — 즉 <em>없는 결정</em>
 * 으로 보인다. ADR-045 를 넣으려고 번호를 세다가 드러났고, 세지 않았으면 계속 비어 있었을 것이다.
 *
 * <p>이 저장소는 서로를 비추는 목록마다 대조를 둔다: 이벤트 스키마 ↔ 예시
 * ({@code EventContractsTest}), 권역 시드 ↔ 지오코더 출력({@code ZoneSeedContractTest}),
 * OpenAPI 문서 ↔ 실제 매핑({@code OpenApiContractIT}), Compose 버전 태그 ↔ 로컬 이미지
 * ({@code make check-images}). ADR 번호 셋만 그 밖에 있었다.
 *
 * <h2>빼는 방식으로 적는다</h2>
 * 검사 대상 번호를 <strong>열거하지 않는다</strong>(§13 규칙 2). 파일에서 전부 읽고, 표에만 있는
 * 번호는 「문서 열이 {@code —} 인가」로 <em>스스로를 설명하게</em> 한다. 그래서 새 ADR 이 생기면
 * 아무것도 고치지 않아도 자동으로 검사 대상이 되고, 제외(결정 방향만 정해 둔 005·008·010·011·012)
 * 는 표 안에 쓰인 채로 읽힌다. 그 대응이 양방향이라 「ADR 을 썼는데 표는 아직 {@code —}」 도 잡힌다.
 *
 * <h2>전제를 먼저 말한다</h2>
 * 파싱이 깨지면 세 집합이 전부 비고, 「빈 집합끼리 같다」는 조용히 통과한다. 그래서 각 검사는
 * 세 출처가 <em>비어 있지 않다</em>를 첫 어설션으로 확인한다(CLAUDE.md 「폴백 테스트는 전제를
 * 첫 어설션으로 스스로 말한다」와 같은 이유다).
 */
@DisplayName("ADR 목록 — 파일 · DESIGN §16 · docs/adr/README.md 는 같은 번호 집합이다")
class AdrIndexConsistencyTest {

    /** {@code ADR-045-revision-comparison-is-per-route.md} 같은 파일명. */
    private static final Pattern ADR_FILE = Pattern.compile("^ADR-(\\d{3})-.*\\.md$");

    /** 표의 첫 칸이 세 자리 번호인 행. 표 밖의 문장은 이 모양이 아니다. */
    private static final Pattern TABLE_ROW = Pattern.compile("^\\|\\s*(\\d{3})\\s*\\|");

    /** 마크다운 링크의 {@code .md} 대상. */
    private static final Pattern MD_LINK = Pattern.compile("\\]\\(([^)]*\\.md)\\)");

    /** 문서 열이 이 글자로 시작하면 「아직 파일이 없다」는 뜻이다 (두 표가 같은 규약을 쓴다). */
    private static final String NO_DOCUMENT = "—";

    private static final Path REPO_ROOT = locateRepoRoot();

    /** 번호 → 파일명. */
    private static final Map<Integer, String> FILES = readAdrFiles();

    private static final Table DESIGN = readTable(
            "docs/DESIGN.md §16",
            section(read(REPO_ROOT.resolve("docs/DESIGN.md")), "## 16. ADR 목록", "\n## 17."));

    private static final Table README = readTable(
            "docs/adr/README.md",
            read(REPO_ROOT.resolve("docs/adr/README.md")));

    @Test
    void 세_목록의_번호_집합이_같다() {
        assertPremise();

        // 차집합을 직접 어설션한다 — `isEqualTo` 는 45개짜리 집합 둘을 통째로 찍어서
        // 정작 「어느 번호가 빠졌나」가 화면 밖으로 밀린다.
        assertThat(missing(DESIGN.numbers(), README.numbers()))
                .as("`docs/DESIGN.md` §16 에만 있고 `docs/adr/README.md` 에는 없는 번호. 둘은 서로를 "
                        + "「같은 내용」이라고 적어 두었다 — 여기 남는 번호가 그 문장이 거짓이 된 자리다")
                .isEmpty();

        assertThat(missing(README.numbers(), DESIGN.numbers()))
                .as("`docs/adr/README.md` 에만 있고 `docs/DESIGN.md` §16 에는 없는 번호")
                .isEmpty();

        assertThat(missing(FILES.keySet(), DESIGN.numbers()))
                .as("파일은 있는데 §16 표에 줄이 없는 ADR — 결정이 쓰였는데 목록에서 보이지 않는다")
                .isEmpty();

        assertThat(missing(FILES.keySet(), README.numbers()))
                .as("파일은 있는데 `docs/adr/README.md` 표에 줄이 없는 ADR (2026-09-19 의 039–044)")
                .isEmpty();
    }

    @Test
    void 표에만_있는_번호는_문서_열이_대시로_스스로_말한다() {
        assertPremise();

        // 열거하지 않는다 — 「표에 있고 파일은 없는 것」이 곧 제외 집합이다.
        Set<Integer> withoutFile = new TreeSet<>(DESIGN.numbers());
        withoutFile.removeAll(FILES.keySet());

        assertThat(DESIGN.numbersMarkedWithoutDocument())
                .as("§16: 문서 열이 `%s` 인 행과 「파일이 없는 번호」가 어긋났다. 양쪽 다 결함이다 — "
                        + "파일 없이 링크를 걸었거나, ADR 을 쓰고도 표를 `%s` 로 두었거나",
                        NO_DOCUMENT, NO_DOCUMENT)
                .isEqualTo(withoutFile);

        assertThat(README.numbersMarkedWithoutDocument())
                .as("`docs/adr/README.md`: 위와 같은 대조")
                .isEqualTo(withoutFile);
    }

    @Test
    void 파일이_있는_번호는_두_표_모두_그_파일을_가리킨다() {
        assertPremise();

        FILES.forEach((number, fileName) -> {
            assertThat(DESIGN.documentLinks(number))
                    .as("§16 의 %03d 행이 가리키는 파일 (이름을 바꾸면 링크가 죽는다)", number)
                    .contains(fileName);
            assertThat(README.documentLinks(number))
                    .as("`docs/adr/README.md` 의 %03d 행이 가리키는 파일", number)
                    .contains(fileName);
        });
    }

    /**
     * 세 출처가 비어 있지 않다 — 파싱이 깨진 채로 「빈 집합끼리 같다」를 통과하지 않게 한다.
     * 그리고 한 표에 같은 번호가 두 줄이면 집합 비교가 그것을 숨긴다.
     */
    private static void assertPremise() {
        assertThat(FILES)
                .as("`docs/adr/` 에서 ADR 파일을 하나도 찾지 못했다 — 저장소 루트 해석이 깨졌다 (%s)",
                        REPO_ROOT)
                .isNotEmpty();
        assertThat(DESIGN.numbers()).as("`docs/DESIGN.md` §16 표를 읽지 못했다").isNotEmpty();
        assertThat(README.numbers()).as("`docs/adr/README.md` 표를 읽지 못했다").isNotEmpty();
        assertThat(DESIGN.duplicates()).as("§16 에 번호가 두 번 나오는 행").isEmpty();
        assertThat(README.duplicates()).as("`docs/adr/README.md` 에 번호가 두 번 나오는 행").isEmpty();
    }

    /** {@code left} 에는 있고 {@code right} 에는 없는 번호. */
    private static Set<Integer> missing(Set<Integer> left, Set<Integer> right) {
        Set<Integer> difference = new TreeSet<>(left);
        difference.removeAll(right);
        return difference;
    }

    // --- 읽기 ----------------------------------------------------------------

    /**
     * 한 표에서 읽어 낸 것 — 번호마다 <em>문서 열</em>(마지막 칸) 하나.
     *
     * @param source     실패 메시지에 쓰는 출처 이름
     * @param lastCell   번호 → 문서 열 원문
     * @param duplicates 같은 번호가 두 번 나온 행
     */
    private record Table(String source, Map<Integer, String> lastCell, List<Integer> duplicates) {

        Set<Integer> numbers() {
            return new TreeSet<>(lastCell.keySet());
        }

        /** 문서 열이 {@code —} 로 시작하는 행의 번호. */
        Set<Integer> numbersMarkedWithoutDocument() {
            Set<Integer> marked = new TreeSet<>();
            lastCell.forEach((number, cell) -> {
                if (cell.startsWith(NO_DOCUMENT)) {
                    marked.add(number);
                }
            });
            return marked;
        }

        /** 그 행의 문서 열이 가리키는 {@code .md} 파일 이름들. */
        List<String> documentLinks(int number) {
            String cell = lastCell.get(number);
            if (cell == null) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            Matcher matcher = MD_LINK.matcher(cell);
            while (matcher.find()) {
                names.add(Paths.get(matcher.group(1)).getFileName().toString());
            }
            return names;
        }
    }

    private static Map<Integer, String> readAdrFiles() {
        Path directory = REPO_ROOT.resolve("docs/adr");
        Map<Integer, String> found = new TreeMap<>();
        try (Stream<Path> entries = Files.list(directory)) {
            entries.forEach(entry -> {
                Matcher matcher = ADR_FILE.matcher(entry.getFileName().toString());
                if (matcher.matches()) {
                    found.put(Integer.parseInt(matcher.group(1)), entry.getFileName().toString());
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("docs/adr 를 읽지 못했습니다: " + directory, e);
        }
        return found;
    }

    private static Table readTable(String source, String markdown) {
        Map<Integer, String> lastCell = new LinkedHashMap<>();
        List<Integer> duplicates = new ArrayList<>();
        for (String line : markdown.split("\n", -1)) {
            Matcher matcher = TABLE_ROW.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            int number = Integer.parseInt(matcher.group(1));
            String[] cells = line.strip().replaceAll("^\\||\\|$", "").split("\\|", -1);
            String document = cells[cells.length - 1].strip();
            if (lastCell.put(number, document) != null) {
                duplicates.add(number);
            }
        }
        return new Table(source, lastCell, duplicates);
    }

    /** {@code heading} 부터 {@code nextHeading} 앞까지. 둘 다 없으면 파싱이 깨진 것이므로 던진다. */
    private static String section(String markdown, String heading, String nextHeading) {
        int start = markdown.indexOf(heading);
        if (start < 0) {
            throw new IllegalStateException("제목을 찾지 못했습니다: " + heading);
        }
        int end = markdown.indexOf(nextHeading, start);
        if (end < 0) {
            throw new IllegalStateException("다음 제목을 찾지 못했습니다: " + nextHeading);
        }
        return markdown.substring(start, end);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("문서를 읽지 못했습니다: " + file, e);
        }
    }

    /**
     * 작업 디렉터리에서 위로 올라가며 {@code docs/adr} 를 찾는다.
     *
     * <p>Gradle 은 테스트의 작업 디렉터리를 <em>모듈</em> 디렉터리로 잡는다({@code libs/common}).
     * 상대 경로를 하드코딩하면 모듈이 옮겨질 때 조용히 깨지므로 루트를 찾아 올라간다
     * ({@code EventContracts} 와 같은 방식).
     */
    private static Path locateRepoRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("docs").resolve("adr"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("docs/adr 를 찾지 못했습니다. 작업 디렉터리=" + current);
    }
}
