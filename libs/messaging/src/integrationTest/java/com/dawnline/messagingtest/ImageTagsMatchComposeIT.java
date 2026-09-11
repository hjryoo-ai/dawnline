package com.dawnline.messagingtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 통합 테스트가 쓰는 인프라 이미지가 Compose 스택과 같은 태그인지 확인한다.
 *
 * <p>{@code gradle/libs.versions.toml} 은 "인프라 이미지 버전은 {@code deploy/compose/.env} 에서만
 * 고정한다" 고 정해 두었는데, Testcontainers 쪽에도 같은 태그가 상수로 한 벌 더 있다.
 * 둘이 어긋나면 아무 신호도 없이 <strong>서로 다른 PostgreSQL·Kafka 를 검증하게 된다</strong> —
 * Compose 는 새 버전으로 뜨는데 {@code integrationTest} 는 계속 옛 버전을 인증해 주는 상태다.
 * 그 어긋남을 여기서 실패로 바꾼다.
 *
 * <p>컨테이너를 띄우지 않는다(파일 비교뿐). {@code integrationTest} 소스셋에 두는 이유는
 * 검증 대상이 이 소스셋의 상수이기 때문이다.
 */
class ImageTagsMatchComposeIT {

    @Test
    void 테스트_이미지_태그가_compose_와_같다() throws IOException {
        Map<String, String> env = readEnvExample();

        assertThat(env)
                .as("deploy/compose/.env.example 에 이미지 태그 키가 있어야 한다")
                .containsKeys("POSTGRES_IMAGE", "KAFKA_IMAGE", "REDIS_IMAGE");
        assertThat(MessagingIntegrationTestBase.POSTGRES_IMAGE)
                .as("Testcontainers 와 Compose 가 같은 PostgreSQL 을 써야 한다")
                .isEqualTo(env.get("POSTGRES_IMAGE"));
        assertThat(MessagingIntegrationTestBase.KAFKA_IMAGE)
                .as("Testcontainers 와 Compose 가 같은 Kafka 를 써야 한다")
                .isEqualTo(env.get("KAFKA_IMAGE"));
        assertThat(MessagingIntegrationTestBase.REDIS_IMAGE)
                .as("Testcontainers 와 Compose 가 같은 Redis 를 써야 한다 — 서비스 IT 들이 쓴다")
                .isEqualTo(env.get("REDIS_IMAGE"));
    }

    private static Map<String, String> readEnvExample() throws IOException {
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(repositoryRoot().resolve("deploy/compose/.env.example"))) {
            String trimmed = line.strip();
            int separator = trimmed.indexOf('=');
            if (trimmed.startsWith("#") || separator < 1) {
                continue;
            }
            values.put(trimmed.substring(0, separator).strip(),
                    trimmed.substring(separator + 1).strip().replaceAll("^\"|\"$", ""));
        }
        return values;
    }

    /** 작업 디렉터리에서 위로 올라가며 {@code settings.gradle.kts} 가 있는 곳을 찾는다. */
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
