package com.dawnline.sim.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * 기사의 출발이 재는 그룹은 tracking 의 그룹이다 — 두 곳에 적힌 같은 값의 대조 (CLAUDE.md 「서로를 비추는 목록에는 대조 검사를 둔다」).
 *
 * <p>틀리면 조용하다. 없는 그룹은 커밋이 없어 {@link com.dawnline.sim.driver.KafkaGroupLag} 가 토픽 전체를 랙으로 세고, 출발은
 * 상한까지 기다린 뒤 「tracking 이 반영하지 못했다」로 실패한다 — 원인은 tracking 이 아니라 이름이다.
 */
class TrackingGroupTest {

    private static final Path TRACKING_CONFIG =
            Path.of("services", "tracking-service", "src", "main", "resources", "application.yml");

    @Test
    void 출발이_재는_그룹은_tracking_의_컨슈머_그룹이다() throws IOException {
        Path file = repoRoot().resolve(TRACKING_CONFIG);
        assertThat(file).as("tracking 의 설정 파일").isRegularFile();

        List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load("tracking", new FileSystemResource(file));
        Object group = loaded.stream().map(source -> source.getProperty("spring.kafka.consumer.group-id"))
                .filter(value -> value != null).findFirst().orElse(null);

        assertThat(group).as("tracking 의 spring.kafka.consumer.group-id").isNotNull();
        assertThat(group.toString()).isEqualTo(SimRunnerConfig.TRACKING_GROUP);
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
