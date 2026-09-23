package com.dawnline.messaging.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.Topics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code deploy/compose} 의 토픽 생성 목록과 {@code contracts/events} 의 스키마가 같은 집합인지
 * (CLAUDE.md 「서로를 비추는 목록에는 대조 검사를 둔다」).
 *
 * <p>둘은 같은 것을 두 번 적은 목록이다 — 스키마가 하나 붙으면 토픽이 하나 붙어야 한다.
 * 브로커는 자동 생성을 꺼 두었으므로(compose 의 {@code KAFKA_AUTO_CREATE_TOPICS_ENABLE=false})
 * 목록에서 빠진 토픽은 로컬 스택에서 <strong>리스너가 조용히 아무것도 받지 않는</strong> 형태로
 * 나타나고, 그것은 어느 테스트도 실패시키지 않는다 — 통합 테스트는 자기 토픽을 스스로 만든다.
 *
 * <p>계기는 {@code delivery.route-departed}(ADR-050)다. §4.1 이 「그 커밋에서 토픽 목록도 함께
 * 는다」라고 적어 두었지만 그 문장을 확인하는 장치가 없었다.
 *
 * <p>양쪽 다 파일에서 읽는다 — 토픽을 여기 열거하지 않는다. 그리고 {@code docker-compose.yml}
 * 을 {@code test} 태스크의 입력으로 선언했다(build.gradle.kts). 아니면 compose 만 고친 빌드에서
 * 이 검사가 {@code UP-TO-DATE} 로 건너뛴다.
 */
class ComposeTopicsTest {

    /** kafka-init 의 {@code TOPICS="…"} 블록 안에 한 줄에 하나씩 적힌 토픽 이름. */
    private static final Pattern TOPIC_LINE =
            Pattern.compile("^\\s*(dawnline\\.[a-z0-9.-]+\\.v\\d+)\\s*$", Pattern.MULTILINE);

    /** 스키마 파일 이름 — 봉투 스키마는 토픽이 아니므로 {@code .v<n>.schema.json} 중 eventType 에 점이 있는 것만. */
    private static final Pattern SCHEMA_FILE =
            Pattern.compile("^([a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+)\\.v(\\d+)\\.schema\\.json$");

    private static final EventContracts CONTRACTS = EventContracts.load();

    @Test
    void compose_가_만드는_토픽과_계약의_스키마가_같은_집합이다() {
        Set<String> composeTopics = composeTopics();
        Set<String> contractTopics = contractTopics();

        assertThat(composeTopics)
                .as("compose 목록을 읽었다 — 비어 있으면 아래 비교는 아무것도 검사하지 않는다")
                .isNotEmpty();
        assertThat(composeTopics)
                .as("deploy/compose/docker-compose.yml 의 kafka-init TOPICS 와 contracts/events/*.schema.json")
                .isEqualTo(contractTopics);
    }

    private static Set<String> composeTopics() {
        Path compose = CONTRACTS.contractsDirectory().getParent().getParent()
                .resolve("deploy/compose/docker-compose.yml");
        try {
            Matcher matcher = TOPIC_LINE.matcher(Files.readString(compose));
            Set<String> topics = new TreeSet<>();
            while (matcher.find()) {
                topics.add(matcher.group(1));
            }
            return topics;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> contractTopics() {
        try (Stream<Path> files = Files.list(CONTRACTS.contractsDirectory())) {
            return files.map(file -> SCHEMA_FILE.matcher(file.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(m -> Topics.forEvent(m.group(1), Integer.parseInt(m.group(2))))
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
