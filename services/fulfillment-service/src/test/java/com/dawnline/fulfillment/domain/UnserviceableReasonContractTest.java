package com.dawnline.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link UnserviceableReason} 과 계약의 권장 어휘가 서로를 비춘다 ({@code contracts/events/README.md} §4.5).
 *
 * <p>계약은 {@code reason} 을 문자열로 두고 권장 어휘를 두 자리에 적는다 — 스키마의 {@code examples} 와 README 의 표.
 * 이 서비스의 enum 은 그 어휘를 실제로 내는 쪽이다. 셋이 갈라지면 하류(order-service · 운영자)는 어휘에 없는 사유를
 * 받고, 그 뜻은 코드에만 있다. 2026-09-25 에 {@code MAX_PUSHES_EXCEEDED} 를 더할 때(ADR-063) 세 자리를 손으로 맞췄다 —
 * 다음 값은 이 검사가 맞추게 한다. 비교는 enum 전체에서 한다(빼는 방식, CLAUDE.md) — 값을 열거하지 않는다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class UnserviceableReasonContractTest {

    private static final Path SCHEMA = Path.of("../../contracts/events/fulfillment.planned.v1.schema.json");
    private static final Path README = Path.of("../../contracts/events/README.md");

    /** README 의 사유 표 행: {@code | `NO_ZONE_MATCH` | 4. …}. */
    private static final Pattern TABLE_ROW = Pattern.compile("(?m)^\\| `([A-Z_]+)` \\|");

    @Test
    void enum_값이_스키마의_reason_examples_와_같다() {
        assertThat(enumValues())
                .as("contracts/events/fulfillment.planned.v1.schema.json 의 reason.examples 와 다릅니다")
                .containsExactlyInAnyOrderElementsOf(schemaExamples());
    }

    @Test
    void enum_값이_README_의_사유_표와_같다() {
        assertThat(enumValues())
                .as("contracts/events/README.md 의 UNSERVICEABLE 사유 표와 다릅니다")
                .containsExactlyInAnyOrderElementsOf(readmeRows());
    }

    private static Set<String> enumValues() {
        Set<String> values = new LinkedHashSet<>();
        Arrays.stream(UnserviceableReason.values()).map(Enum::name).forEach(values::add);
        return values;
    }

    private static Set<String> schemaExamples() {
        JsonNode reason = JsonMapper.builder().build().readTree(read(SCHEMA)).path("properties").path("reason");
        JsonNode examples = reason.path("examples");
        assertThat(examples.isArray()).as("properties.reason.examples 를 찾지 못했습니다 — 스키마 모양이 바뀌었다").isTrue();
        Set<String> values = new LinkedHashSet<>();
        examples.forEach(node -> values.add(node.stringValue()));
        return values;
    }

    /** README 에서 사유 표 하나만 읽는다 — 머리가 「| 값 | §5.2 단계 |」인 표. */
    private static Set<String> readmeRows() {
        String text = read(README);
        int start = text.indexOf("| 값 | §5.2 단계 |");
        assertThat(start).as("README 의 사유 표 머리를 찾지 못했습니다 — 표 모양이 바뀌었다").isNotNegative();
        int end = text.indexOf("\n\n", start);
        Matcher rows = TABLE_ROW.matcher(text.substring(start, end < 0 ? text.length() : end));
        Set<String> values = new LinkedHashSet<>();
        while (rows.find()) {
            values.add(rows.group(1));
        }
        return values;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("계약 파일을 읽을 수 없습니다: " + path.toAbsolutePath(), e);
        }
    }
}
