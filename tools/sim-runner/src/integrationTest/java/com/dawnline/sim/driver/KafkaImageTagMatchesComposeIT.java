package com.dawnline.sim.driver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 이 모듈의 Kafka 이미지 태그가 Compose 스택과 같은지.
 *
 * <p>{@code libs/messaging} 의 {@code ImageTagsMatchComposeIT} 와 같은 이유이고 같은 방식이다 —
 * 태그 상수가 늘어날 때마다 대조도 함께 늘어나야 한다. 어긋나면 아무 신호 없이
 * <strong>서로 다른 Kafka 를 검증하게 된다</strong>.
 *
 * <p>컨테이너를 띄우지 않는다(파일 비교뿐).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class KafkaImageTagMatchesComposeIT {

    @Test
    void 테스트_이미지_태그가_compose_와_같다() throws IOException {
        assertThat(SimDriverIT.KAFKA_IMAGE)
                .as("Testcontainers 와 Compose 가 같은 Kafka 를 써야 한다")
                .isEqualTo(envValue("KAFKA_IMAGE"));
    }

    private static String envValue(String key) throws IOException {
        for (String line : Files.readAllLines(repositoryRoot().resolve("deploy/compose/.env.example"))) {
            String trimmed = line.strip();
            int separator = trimmed.indexOf('=');
            if (!trimmed.startsWith("#") && separator > 0 && trimmed.substring(0, separator).strip().equals(key)) {
                return trimmed.substring(separator + 1).strip().replaceAll("^\"|\"$", "");
            }
        }
        throw new AssertionError("deploy/compose/.env.example 에 %s 가 없습니다".formatted(key));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("저장소 루트를 찾을 수 없습니다(settings.gradle.kts 기준)");
        }
        return current;
    }
}
