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
 * 모든 Kafka 컨테이너는 기동에 세 번까지 새로 띄운다 (DESIGN.md §13 「Kafka 컨테이너는 기동에 세 번까지」).
 *
 * <p>#93 의 CI 에서 {@code KafkaGroupLagIT} 의 컨테이너가 기동 중에 exit 126 으로 죽었다 — 타임아웃 메시지는 죽은 컨테이너의 로그를
 * 기다린 결과였다. 처방({@code withStartupAttempts})은 컨테이너를 만드는 자리마다 붙어야 하고, 그 자리는 모듈마다 따로 있다. 하나를 빠뜨리면
 * 그 IT 만 가끔 빨갛고 — 가끔 실패하는 게이트는 가끔 검사하는 게이트다.
 *
 * <p>빼는 방식으로 적는다: 파일을 열거하지 않고 저장소의 {@code .java} 전부에서 {@code new KafkaContainer(} 로 시작하는 문장을 찾는다.
 * 제외는 이 파일 하나다(음성 표본의 문자열).
 */
@DisplayName("Kafka 컨테이너는 모두 기동 재시도를 건다")
class KafkaContainerStartupTest {

    /** {@code new KafkaContainer(} 에서 그 문장의 끝({@code ;})까지. */
    private static final Pattern CONTAINER = Pattern.compile("new KafkaContainer\\([^;]*;", Pattern.DOTALL);

    private static final Path REPO_ROOT = locateRepoRoot();

    @Test
    void 저장소의_KafkaContainer_는_모두_withStartupAttempts_를_건다() {
        List<Path> sources = javaSources();
        List<String> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Path source : sources) {
            Matcher matcher = CONTAINER.matcher(read(source));
            while (matcher.find()) {
                String where = REPO_ROOT.relativize(source).toString();
                found.add(where);
                if (!retries(matcher.group())) {
                    missing.add(where);
                }
            }
        }
        // 전제: 무엇을 찾았는가. 경로가 깨져 아무것도 찾지 못하면 「빠진 것 0」은 조용히 참이다.
        assertThat(found).as("전제 — 컨테이너를 만드는 자리를 찾았다").hasSizeGreaterThanOrEqualTo(9)
                .anyMatch(path -> path.endsWith("KafkaGroupLagIT.java"));
        assertThat(missing).as("기동 중에 죽은 컨테이너는 새로 띄운다 — DESIGN §13").isEmpty();
    }

    @Test
    void 검사는_빠진_것을_잡고_건_것을_지나간다() {
        // 음성 표본 — 이 패턴이 잡아야 할 것을 실제로 잡는가.
        Matcher bare = CONTAINER.matcher("static final KafkaContainer K = new KafkaContainer(IMAGE)\n    .withEnv(\"A\", \"b\");");
        assertThat(bare.find()).isTrue();
        assertThat(retries(bare.group())).isFalse();
        Matcher armed = CONTAINER.matcher("new KafkaContainer(IMAGE)\n    .withStartupAttempts(3);");
        assertThat(armed.find()).isTrue();
        assertThat(retries(armed.group())).isTrue();
    }

    private static boolean retries(String statement) {
        return statement.contains(".withStartupAttempts(");
    }

    private static List<Path> javaSources() {
        try (Stream<Path> files = Files.walk(REPO_ROOT)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith("KafkaContainerStartupTest.java"))
                    .filter(path -> {
                        String relative = REPO_ROOT.relativize(path).toString();
                        return !relative.contains("/build/") && !relative.startsWith(".")
                                && !relative.contains("node_modules");
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
