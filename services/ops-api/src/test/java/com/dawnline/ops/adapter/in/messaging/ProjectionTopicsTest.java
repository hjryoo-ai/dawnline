package com.dawnline.ops.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.Topics;
import com.dawnline.messaging.contract.EventContracts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * ops 는 §4.1 의 토픽 <strong>전부</strong>를 구독한다(§5.5 「모든 토픽을 구독해 읽기 모델을 갱신」).
 *
 * <p>기대 집합을 여기 열거하지 않는다 — 계약 디렉터리의 스키마에서 읽는다(§13 규칙 2). 그래서
 * 토픽이 하나 붙으면(계약이 먼저 온다, 불변규칙 8) 리스너에 한 줄이 붙을 때까지 이 검사가 빨갛다.
 * {@code @KafkaListener} 의 토픽 이름 오타도 여기서 잡힌다 — 오타는 컨슈머가 <em>조용히 아무것도
 * 받지 않는</em> 형태로 나타난다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ProjectionTopicsTest {

    private static final Pattern SCHEMA_FILE =
            Pattern.compile("^([a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+)\\.v(\\d+)\\.schema\\.json$");

    @Test
    void 리스너가_계약에_있는_토픽을_전부_구독한다() throws IOException {
        Set<String> contractTopics;
        try (Stream<Path> files = Files.list(EventContracts.load().contractsDirectory())) {
            contractTopics = files.map(file -> SCHEMA_FILE.matcher(file.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(m -> Topics.forEvent(m.group(1), Integer.parseInt(m.group(2))))
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        assertThat(contractTopics).as("계약을 읽었다 — 비어 있으면 아래 비교는 공허하다").hasSizeGreaterThanOrEqualTo(11);
        assertThat(ListenerTopics.of().keySet()).isEqualTo(contractTopics);
    }

    @Test
    void 소비자_이름이_서비스_이름이다() {
        // processed_events.consumer 값이다. 인스턴스마다 달라지면 멱등이 깨진다 (§8.5).
        assertThat(ProjectionListener.CONSUMER).isEqualTo("ops-api");
    }
}
