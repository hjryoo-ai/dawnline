package com.dawnline.common.docs;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 시계는 하나다 — 운영 코드의 SQL 은 DB 의 시계를 읽지 않는다 (CLAUDE.md 불변규칙 12, ADR-066 결정 4).
 *
 * <h2>왜 이 테스트가 생겼나</h2>
 * 시뮬레이션 오프셋(ADR-066)은 JVM 의 주입 시계에만 닿는다. SQL 의 {@code now()} 는 DB 의 벽시계라, 주입 시계로 적은 칸과 비교하면
 * 오프셋만큼 어긋난다 — outbox 의 미발행 나이가 <strong>−28,799.95초</strong>가 됐다({@code OutboxLagIT} 를 옛 SQL 에 먼저 돌렸다).
 * ArchUnit 은 {@code Instant.now()} 같은 <em>호출</em>은 보지만 문자열 안의 SQL 은 보지 못한다. 그 빈자리를 이 테스트가 맡는다.
 *
 * <h2>빼는 방식으로 적는다</h2>
 * 검사할 파일을 열거하지 않는다 — 저장소의 {@code src/main} 아래 {@code .java} 전부의 <strong>문자열 리터럴</strong>(텍스트 블록 포함)과
 * {@code .sql} · {@code .lua} 전부를 읽고, 제외는 하나다:
 * <ul>
 *   <li><strong>{@code db/migration/}</strong> — 마이그레이션은 기동 때 한 번 도는 스키마 · 채우기다. 거기의 {@code now()} ·
 *       {@code CURRENT_DATE} 는 사실의 시각이 아니다(V1 · V3 · V6 이 주석으로 같은 말을 한다). 그리고 {@code V} 는 불변이다(규칙 13).
 *       {@code DEFAULT now()} 가 붙은 칸 셋({@code rm_waves} · {@code rm_routes} · {@code shipments} 의 {@code updated_at})은
 *       어댑터가 언제나 값을 적는다 — ADR-066 결정 4 에 적었다.</li>
 * </ul>
 * 주석은 리터럴이 아니라서 읽지 않는다 — 「DB 의 {@code now()} 를 쓰지 않는다」는 문장을 쓸 수 있어야 한다.
 */
@DisplayName("운영 코드의 SQL 은 DB 시계(now() · CURRENT_TIMESTAMP …)를 읽지 않는다")
class SqlUsesInjectedClockTest {

    /** DB 의 시계 — 함수(괄호 둘)와 SQL 표준 키워드. {@code Instant.now()} 처럼 점 뒤의 호출은 자바다. */
    static final Pattern DB_CLOCK = Pattern.compile(
            "(?<![.\\w])(?i:now|clock_timestamp|statement_timestamp|transaction_timestamp)\\s*\\(\\s*\\)"
                    + "|\\b(?i:current_timestamp|current_date|current_time|localtimestamp)\\b");

    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);

    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    private static final Path REPO_ROOT = locateRepoRoot();

    @Test
    void 운영_소스의_SQL_에_DB_시계가_없다() {
        List<Path> sources = productionSources();
        // 전제: 무엇을 읽었는가. 경로가 깨져 아무것도 읽지 않으면 「위반 0」은 조용히 참이다.
        assertThat(sources).as("전제 — 운영 소스를 읽었다").hasSizeGreaterThan(100)
                .anyMatch(p -> p.endsWith("JpaOutboxRepository.java"))
                .anyMatch(p -> p.toString().endsWith(".lua"));

        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            for (String sql : sqlOf(source)) {
                Matcher matcher = DB_CLOCK.matcher(sql);
                while (matcher.find()) {
                    violations.add(REPO_ROOT.relativize(source) + ": " + matcher.group().strip());
                }
            }
        }
        assertThat(violations).as("주입 시계의 값을 파라미터로 받는다(ADR-066 결정 4)").isEmpty();
    }

    @Test
    void 검사는_SQL_의_now_를_잡고_자바의_호출과_주석은_지나간다() {
        // 음성 표본 — 이 패턴이 잡아야 할 것을 실제로 잡는가.
        assertThat(literals("String q = \"\"\"\n SELECT now() - min(created_at)\n\"\"\";"))
                .anySatisfy(sql -> assertThat(DB_CLOCK.matcher(sql).find()).isTrue());
        assertThat(literals("jdbc.update(\"UPDATE t SET updated_at = CURRENT_TIMESTAMP\");"))
                .anySatisfy(sql -> assertThat(DB_CLOCK.matcher(sql).find()).isTrue());
        assertThat(literals("jdbc.update(\"UPDATE t SET at = NOW( )\");"))
                .anySatisfy(sql -> assertThat(DB_CLOCK.matcher(sql).find()).isTrue());
        // 양성 — 자바의 호출 · 주석 · 비슷한 이름.
        assertThat(literals("Instant x = Instant.now(); // SQL 의 now() 를 쓰지 않는다\nrefreshNow();")).isEmpty();
        assertThat(DB_CLOCK.matcher("SELECT refreshNow() , LocalTime").find()).isFalse();
    }

    /** 운영 소스 — {@code src/main} 아래, 산출물 · 마이그레이션 제외. */
    private static List<Path> productionSources() {
        try (Stream<Path> files = Files.walk(REPO_ROOT)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String path = REPO_ROOT.relativize(p).toString().replace('\\', '/');
                        return path.contains("/src/main/")
                                && !path.contains("/build/") && !path.contains("/node_modules/")
                                && !path.contains("/db/migration/")
                                && (path.endsWith(".java") || path.endsWith(".sql") || path.endsWith(".lua"));
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 자바는 문자열 리터럴만, SQL · Lua 는 파일 전체. */
    private static List<String> sqlOf(Path source) {
        String text;
        try {
            text = Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return source.toString().endsWith(".java") ? literals(text) : List.of(text);
    }

    static List<String> literals(String java) {
        List<String> found = new ArrayList<>();
        Matcher blocks = TEXT_BLOCK.matcher(java);
        StringBuilder rest = new StringBuilder();
        while (blocks.find()) {
            found.add(blocks.group(1));
            blocks.appendReplacement(rest, "");
        }
        blocks.appendTail(rest);
        // 한 줄 주석 뒤의 따옴표는 리터럴이 아니다 — 줄마다 // 앞까지만 본다(문자열 안의 // 는 SQL 에 없다).
        for (String line : rest.toString().split("\n")) {
            int comment = line.indexOf("//");
            Matcher strings = STRING_LITERAL.matcher(comment >= 0 ? line.substring(0, comment) : line);
            while (strings.find()) {
                found.add(strings.group(1));
            }
        }
        return found;
    }

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
