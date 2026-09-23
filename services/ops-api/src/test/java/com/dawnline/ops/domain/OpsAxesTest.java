package com.dawnline.ops.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 네 축의 선언 순서가 설계서 §5.5 「DDL 정정」 표와 같은가 (CLAUDE.md 「서로를 비추는 목록에는
 * 대조 검사를 둔다」).
 *
 * <p>축은 enum 의 <strong>선언 순서</strong>라서 값 두 줄을 바꿔 적는 것만으로 판정이 뒤집힌다 —
 * 컴파일도 되고 다른 테스트도 대개 통과한다(서로 바꾼 두 값이 한 주문에 함께 오지 않는 쌍이면).
 * 설계서의 표가 순서의 기준이다. 이 검사가 읽는 {@code docs/DESIGN.md} 는 test 태스크의 입력으로
 * 선언했다(build.gradle.kts).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OpsAxesTest {

    /** 표의 칸 이름 → 그 칸의 축. */
    static Map<String, Class<? extends Enum<?>>> axes() {
        return Map.of(
                "`order_status`", OrderStatus.class,
                "`delivery_outcome`", DeliveryOutcome.class,
                "`rm_waves.status`", WaveStatus.class,
                "`rm_routes.status`", RouteStatus.class);
    }

    static List<String> columns() {
        return new ArrayList<>(axes().keySet());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("columns")
    void 선언_순서가_설계서의_축과_같다(String column) throws IOException {
        List<String> declared = Arrays.stream(axes().get(column).getEnumConstants()).map(Enum::name).toList();

        assertThat(designAxis(column)).as("§5.5 「DDL 정정」 표의 %s 축", column).isEqualTo(declared);
    }

    /** 표의 그 행에서 {@code A(0) → B(1) → …} 를 읽어 번호 순서대로 돌려준다. */
    private static List<String> designAxis(String column) throws IOException {
        Pattern row = Pattern.compile("^\\| " + Pattern.quote(column) + " \\|.*\\| `([^`]*)` \\|$", Pattern.MULTILINE);
        Matcher matcher = row.matcher(Files.readString(design()));
        assertThat(matcher.find()).as("§5.5 표에 %s 행이 있다", column).isTrue();
        List<String> values = new ArrayList<>();
        Matcher step = Pattern.compile("([A-Z_]+)\\((\\d+)\\)").matcher(matcher.group(1));
        while (step.find()) {
            assertThat(Integer.parseInt(step.group(2))).as("번호가 순서대로다").isEqualTo(values.size());
            values.add(step.group(1));
        }
        return values;
    }

    private static Path design() {
        for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("docs/DESIGN.md");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("docs/DESIGN.md 를 찾지 못했다");
    }
}
