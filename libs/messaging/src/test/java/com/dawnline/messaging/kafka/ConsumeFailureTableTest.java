package com.dawnline.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.docs.RetentionTable;
import com.dawnline.messaging.FailureKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * ADR-015 후속 정정의 경계표 ↔ {@link ConsumeFailure} 대조 (CLAUDE.md 「서로를 비추는 목록에는 대조 검사를 둔다」).
 *
 * <p>정본은 enum 이다. 문서의 표는 행 이름 · 판정 · <strong>순서</strong>까지 같아야 한다 — 순서가 곧 판정 순서이기 때문이다.
 * 문서에만 있는 행은 첫 칸이 {@code —} 이고 둘째 칸이 「해당 없음」으로 <strong>스스로 설명한다</strong>(HTTP 행).
 *
 * <p>이 테스트가 읽는 ADR 은 {@code test} 태스크의 입력이다 — 문서만 고친 실행이 {@code UP-TO-DATE} 로 건너뛰지 않게.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ConsumeFailureTableTest {

    private static final String ADR = "docs/adr/ADR-015-outbox-publish-side-quarantine.md";

    private static final String HEADING = "### 경계표";

    private static final Pattern REASON = Pattern.compile("^`([a-z_]+)`$");

    @Test
    void 문서의_표는_enum_의_행_이름_판정_순서와_같다() throws IOException {
        List<String> documented = new ArrayList<>();
        for (List<String> cells : table()) {
            Matcher reason = REASON.matcher(cells.get(0));
            if (reason.matches()) {
                documented.add(reason.group(1) + " " + cells.get(2));
            }
        }

        List<String> code = Arrays.stream(ConsumeFailure.values())
                .map(row -> row.reason() + " " + verdict(row.kind()))
                .toList();

        assertThat(documented).as("ADR-015 경계표 (행 · 판정 — 위에서부터)").isEqualTo(code);
    }

    @Test
    void 문서에만_있는_행은_해당_없음으로_스스로_설명한다() throws IOException {
        List<List<String>> documentOnly = table().stream()
                .filter(cells -> !REASON.matcher(cells.get(0)).matches())
                .toList();

        assertThat(documentOnly).as("전제 — HTTP 행이 표에 있다").isNotEmpty();
        assertThat(documentOnly).allSatisfy(cells -> {
            assertThat(cells.get(0)).startsWith("—");
            assertThat(cells.get(1)).contains("해당 없음");
            assertThat(cells.get(2)).isEqualTo("—");
        });
    }

    private static String verdict(FailureKind kind) {
        return switch (kind) {
            case DETERMINISTIC -> "결정적";
            case TRANSIENT -> "일시적";
        };
    }

    /** 「### 경계표」 아래 첫 표의 본문 행 — 머리와 구분 줄은 뺀다. 표가 없으면 실패한다. */
    private static List<List<String>> table() throws IOException {
        List<String> lines = Files.readAllLines(RetentionTable.locateRepoRoot().resolve(ADR), StandardCharsets.UTF_8);
        int start = lines.indexOf(HEADING);
        assertThat(start).as("%s 에 「%s」 절이 있다", ADR, HEADING).isNotNegative();
        List<List<String>> rows = new ArrayList<>();
        boolean inTable = false;
        for (String line : lines.subList(start + 1, lines.size())) {
            if (line.startsWith("|")) {
                inTable = true;
                List<String> cells = Arrays.stream(line.substring(1, line.lastIndexOf('|')).split("\\|", -1))
                        .map(String::strip)
                        .toList();
                if (!cells.getFirst().equals("행 (`reason`)") && !cells.getFirst().startsWith("---")) {
                    rows.add(cells);
                }
            } else if (inTable) {
                break;
            }
        }
        assertThat(rows).as("경계표의 행").isNotEmpty();
        return rows;
    }
}
