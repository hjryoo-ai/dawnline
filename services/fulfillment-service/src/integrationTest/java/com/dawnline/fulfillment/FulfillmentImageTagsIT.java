package com.dawnline.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 이 모듈의 테스트 컨테이너 태그가 {@code deploy/compose/.env.example} 과 같은지 검사한다.
 *
 * <p>{@code libs/messaging} 의 {@code ImageTagsMatchComposeIT} 와 같은 이유다 — 컨테이너에서는
 * 되는데 Compose 에서는 안 되는(또는 그 반대) 상황을 없앤다. <strong>이 검사를 붙인 것은 실제로
 * 드리프트가 났기 때문이다</strong>: Redis 컨테이너를 추가하며 태그를 8.2.2 로 적었는데
 * {@code .env.example} 은 8.8.2 였다.
 *
 * <p>컨테이너를 띄우지 않는다(파일 비교뿐). {@code integrationTest} 소스셋에 두는 이유는 검증
 * 대상이 이 소스셋의 상수이기 때문이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("FulfillmentImageTagsIT — 테스트 이미지 태그가 compose 와 같다")
class FulfillmentImageTagsIT {

    @Test
    void 테스트_이미지_태그가_compose_와_같다() throws IOException {
        Map<String, String> env = readEnvExample();

        assertThat(env).containsKeys("POSTGRES_IMAGE", "REDIS_IMAGE");
        assertThat(FulfillmentIntegrationTestBase.POSTGRES_IMAGE)
                .as("Testcontainers 와 Compose 가 같은 PostgreSQL 을 써야 한다")
                .isEqualTo(env.get("POSTGRES_IMAGE"));
        assertThat(FulfillmentIntegrationTestBase.REDIS_IMAGE)
                .as("Testcontainers 와 Compose 가 같은 Redis 를 써야 한다")
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
