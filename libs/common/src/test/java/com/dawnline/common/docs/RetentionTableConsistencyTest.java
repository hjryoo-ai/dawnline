package com.dawnline.common.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * §7.1 보존 표의 모든 행이 <strong>어느 모듈의 대조에 걸려 있는지</strong> 본다 (ADR-058 결정 7).
 *
 * <p>값의 대조는 설정을 가진 모듈마다의 {@code RetentionTableDefaultsTest} 가 한다 — 이 모듈({@code libs/common})은
 * 서비스의 설정 레코드를 볼 수 없다(의존 방향). 그래서 여기서는 그 대조가 빠짐없이 있는지를 본다: 표에 행을 하나
 * 더하고 어느 모듈에도 대조를 두지 않으면 그 행은 쓴 날의 값으로 남는다. 반대 방향(설정에는 있는데 표에 없는
 * 기간)은 모듈 테스트가 양방향으로 본다.
 *
 * <p>검사는 <strong>빼는 방식</strong>이다(CLAUDE.md) — 행을 열거하지 않고 표에서 전부 읽는다. 설정 키가 없는 행은
 * 그 칸(`—`)과 「무기한」으로 스스로 설명한다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("§7.1 보존 표 — 모든 행이 대조에 걸려 있다 (ADR-058)")
class RetentionTableConsistencyTest {

    private static final Path REPO_ROOT = RetentionTable.locateRepoRoot();

    /** 모듈의 대조 테스트 이름 — 모듈마다 하나. */
    private static final String DEFAULTS_TEST = "RetentionTableDefaultsTest.java";

    @Test
    void 표를_읽고_모든_기간을_해석한다() {
        List<RetentionTable.Row> rows = RetentionTable.rows();

        assertThat(rows).as("전제 — 표가 비면 아래 검사가 전부 공허하다").hasSizeGreaterThanOrEqualTo(10);
        rows.forEach(RetentionTable.Row::duration);
    }

    @Test
    void 설정_키가_없는_행은_무기한이고_무기한은_설정_키가_없다() {
        for (RetentionTable.Row row : RetentionTable.rows()) {
            assertThat(row.key().isEmpty())
                    .as("%s — 설정 키가 없는 행은 무기한으로 스스로 설명한다(기간이 있으면 그 기간이 사는 설정이 있다)",
                            row.label())
                    .isEqualTo(row.duration().isEmpty());
        }
    }

    @Test
    void 설정_키가_있는_행은_어느_모듈의_대조_테스트가_그_키를_본다() {
        List<String> defaultsTests = sources(DEFAULTS_TEST);
        assertThat(defaultsTests).as("전제 — 대조 테스트를 하나도 찾지 못했다(루트: %s)", REPO_ROOT).isNotEmpty();

        for (RetentionTable.Row row : RetentionTable.rows()) {
            row.key().ifPresent(key -> assertThat(defaultsTests)
                    .as("%s 의 설정 키 %s 를 대조하는 %s 가 없다 — 그 행은 쓴 날의 값으로 남는다", row.label(), key,
                            DEFAULTS_TEST)
                    .anyMatch(source -> source.contains('"' + key + '"')));
        }
    }

    @Test
    void 기간이_있는_표는_그_이름으로_성공_나이_게이지를_낸다() {
        // 게이지의 table 라벨이 표의 첫 열이다(ADR-058 결정 6) — 정리가 있는 표는 전부 그 이름으로 등록한다.
        List<String> mains = mainSources();
        for (RetentionTable.Row row : RetentionTable.rows()) {
            if (row.duration().isEmpty()) {
                continue;
            }
            String registration = ".table(\"" + row.table() + "\")";
            assertThat(mains)
                    .as("%s — 정리가 %s 로 등록하지 않는다. 그 표의 정리는 실패해도 보이지 않는다", row.label(),
                            registration)
                    .anyMatch(source -> source.contains(registration));
        }
    }

    /** 저장소에서 이름이 같은 테스트 소스의 내용. 빌드 산출물은 뺀다. */
    private static List<String> sources(String fileName) {
        return walk(path -> path.getFileName().toString().equals(fileName));
    }

    /** 서비스와 라이브러리의 운영 소스. */
    private static List<String> mainSources() {
        return walk(path -> path.toString().endsWith(".java")
                && path.toString().contains("/src/main/java/"));
    }

    private static List<String> walk(java.util.function.Predicate<Path> filter) {
        try (Stream<Path> paths = Files.walk(REPO_ROOT)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().contains("/build/") && !path.toString().contains("/node_modules/"))
                    .filter(filter)
                    .map(RetentionTableConsistencyTest::read)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("읽지 못했습니다: " + file, e);
        }
    }
}
