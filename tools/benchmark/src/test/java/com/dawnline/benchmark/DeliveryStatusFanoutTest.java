package com.dawnline.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.dawnline.dispatch.domain.PlanMode;
import com.dawnline.dispatch.domain.optimizer.PlanningBudget;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.StopMerger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 주문 rps 를 {@code delivery.status} 건/초로 바꾸는 배율 — <strong>문서와 코드를 맞대 본다.</strong>
 *
 * <h2>왜 테스트가 필요한가</h2>
 * {@code docs/benchmarks/phase5-delivery-status-throughput.md} 의 처리량 기준선은 «주문 하나가
 * 이벤트 몇 건이 되는가» 위에 서 있고, 그 값을 정하는 것은 문서가 아니라 {@link StopMerger} 의
 * 통합 키다(§6.5 1단계). 키가 바뀌면 배율이 바뀌지만 <strong>문서는 그대로 남는다</strong> —
 * 그리고 그렇게 남은 숫자는 틀렸다는 표시 없이 계속 인용된다. 서로를 비추는 두 곳에는 대조
 * 검사를 둔다({@code docs/DESIGN.md} §13, {@code AdrIndexConsistencyTest} 와 같은 계열).
 *
 * <h2>무엇을 대조하나</h2>
 * <ul>
 *   <li>표의 <strong>데이터셋 집합</strong>이 {@link Dataset} 전부와 같다 — 드는 방식이 아니라
 *       <em>빼는 방식</em>이다. 데이터셋이 하나 늘면 표에 줄이 생길 때까지 빨갛다.</li>
 *   <li>각 줄의 다섯 칸(주문 · 통합 후 stop · 주문/stop · 이벤트 · 주문당 이벤트)이 계산값과 같다.</li>
 *   <li>결론 줄의 <strong>{@code 600 rps × 배율 = 건/초}</strong> 가 산수로 맞고, 입력 600 이
 *       {@code docs/DESIGN.md} §8.2 에 실제로 적힌 값이다.</li>
 * </ul>
 *
 * <p>측정은 재현 가능해야 하므로 seed 와 계획 시각을 {@code DatasetFeasibilityTest} 와 같은
 * 값으로 고정한다. <strong>seed 가 바뀌면 배율도 바뀐다</strong> — 그래서 문서에도 적혀 있다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("delivery.status 팬아웃 배율 — 문서와 StopMerger 가 같은 말을 한다")
class DeliveryStatusFanoutTest {

    /** {@code DatasetFeasibilityTest} 와 같은 값. 배율은 seed 에 따라 달라진다. */
    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final long SEED = 20_260_905L;
    private static final PlanningBudget BUDGET =
            new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3));

    /**
     * stop 하나가 내는 {@code delivery.status} 건수 — 도착 + 종결.
     *
     * <p>{@code DEPARTED_CAMP} 는 브로커로 나가지 않는다(5-1b). 이 상수의 <em>권위</em>는 여기가
     * 아니라 {@code TrackingPublishIT.팬아웃은_스캔당_한_건이고_캠프_출발은_0_이다} 이고, 거기서
     * {@code 2 × stop 수} 로 어설션한다. 여기서는 그 값을 <strong>쓰기만</strong> 한다.
     */
    private static final int EVENTS_PER_STOP = 2;

    private static final Path REPO_ROOT = locateRepoRoot();
    private static final Path DOCUMENT =
            REPO_ROOT.resolve("docs/benchmarks/phase5-delivery-status-throughput.md");

    /** {@code | `small` | 500 | 470 | 1.0638 | 940 | 1.8800 |} */
    private static final Pattern ROW = Pattern.compile(
            "^\\|\\s*`([a-z]+)`\\s*\\|\\s*([\\d,]+)\\s*\\|\\s*([\\d,]+)\\s*\\|\\s*([\\d.]+)"
                    + "\\s*\\|\\s*([\\d,]+)\\s*\\|\\s*([\\d.]+)\\s*\\|\\s*$");

    /** {@code > **600 rps × 1.1215 = 673 건/초.**} */
    private static final Pattern CONCLUSION = Pattern.compile(
            "([\\d,]+)\\s*rps\\s*×\\s*([\\d.]+)\\s*=\\s*([\\d,]+)\\s*건/초");

    private static final Map<String, Row> DOCUMENTED = readRows();

    /** 문서 한 줄. */
    private record Row(int orders, int stops, double ordersPerStop, int events, double eventsPerOrder) { }

    /** 코드가 계산한 한 줄. */
    private static Row measured(Dataset dataset) {
        PlanningProblem problem = new DatasetGenerator(dataset, SEED, START)
                .generate(RuleSet.empty(), BUDGET, PlanMode.FULL, 1.0d);
        List<Stop> stops = StopMerger.merge(problem.candidates());
        int orders = problem.candidates().size();
        int events = EVENTS_PER_STOP * stops.size();
        return new Row(orders, stops.size(), (double) orders / stops.size(), events,
                (double) events / orders);
    }

    /**
     * 파싱이 깨진 채 「빈 집합끼리 같다」로 통과하지 않게 한다 — 이 테스트가 읽는 것은
     * 저장소의 문서이고, 경로가 어긋나면 어설션이 아니라 <em>전제</em>가 무너진다.
     */
    @Test
    void 전제_문서의_표를_읽었다() {
        assertThat(Files.exists(DOCUMENT))
                .as("측정 조건 문서가 없다 (%s) — 저장소 루트 해석이 깨졌거나 문서가 지워졌다", DOCUMENT)
                .isTrue();
        assertThat(DOCUMENTED)
                .as("`%s` 의 팬아웃 표에서 한 줄도 읽지 못했다", DOCUMENT.getFileName())
                .isNotEmpty();
    }

    /**
     * 데이터셋 집합을 <strong>빼는 방식</strong>으로 본다. 표에 이름을 나열하지 않는 이유는
     * {@code DatasetFeasibilityTest} 가 {@code peak} 을 목록에 빠뜨렸던 일과 같다 — 드는 방식은
     * 새 구성원이 조용히 검사 밖에 남고 그 사실이 어디에도 나타나지 않는다.
     */
    @Test
    void 표의_데이터셋_집합이_Dataset_전부와_같다() {
        Set<String> code = new TreeSet<>();
        for (Dataset dataset : Dataset.values()) {
            code.add(dataset.cliName());
        }
        Set<String> document = new TreeSet<>(DOCUMENTED.keySet());

        assertThat(difference(code, document))
                .as("데이터셋인데 문서 표에 줄이 없다 — 배율을 모르는 데이터셋이 생겼다는 뜻이고, "
                        + "그 데이터셋으로 §8.2 를 환산하면 근거 없는 수가 나온다")
                .isEmpty();
        assertThat(difference(document, code))
                .as("문서 표에만 있고 코드에는 없는 데이터셋 — 지워진 데이터셋의 줄이 남아 있다")
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Dataset.class)
    void 문서의_배율이_StopMerger_가_내는_값과_같다(Dataset dataset) {
        Row documented = DOCUMENTED.get(dataset.cliName());
        assertThat(documented).as("%s 의 줄", dataset.cliName()).isNotNull();
        Row measured = measured(dataset);

        assertThat(measured.orders()).as("%s 주문 수", dataset.cliName())
                .isEqualTo(documented.orders());
        assertThat(measured.stops())
                .as("%s 통합 후 stop 수 — 바뀌었다면 StopMerger 의 통합 키(§6.5 1단계)가 움직인 "
                                + "것이고, 그러면 `%s` 의 기준선 전체가 무효다",
                        dataset.cliName(), DOCUMENT.getFileName())
                .isEqualTo(documented.stops());
        assertThat(measured.ordersPerStop()).as("%s 주문/stop", dataset.cliName())
                .isCloseTo(documented.ordersPerStop(), within(0.0001d));
        assertThat(measured.events()).as("%s delivery.status 건수", dataset.cliName())
                .isEqualTo(documented.events());
        assertThat(measured.eventsPerOrder()).as("%s 주문당 이벤트", dataset.cliName())
                .isCloseTo(documented.eventsPerOrder(), within(0.0001d));
    }

    /**
     * 결론 줄의 산수와 <strong>입력의 출처</strong>를 함께 본다.
     *
     * <p>산수만 보면 «600» 이 어디서 왔는지는 아무도 묻지 않는다. §8.2 가 다른 수로 바뀌면 이
     * 기준선은 옛 부하 모델의 값이 되고, 그 사실은 두 문서를 나란히 놓기 전에는 보이지 않는다.
     */
    @Test
    void 결론_줄의_환산이_맞고_600_rps_는_DESIGN_8_2_의_값이다() {
        String text = read(DOCUMENT);
        Matcher matcher = CONCLUSION.matcher(text);
        assertThat(matcher.find())
                .as("`%s` 에서 «N rps × 배율 = M 건/초» 결론 줄을 찾지 못했다", DOCUMENT.getFileName())
                .isTrue();

        int rps = number(matcher.group(1));
        double multiplier = Double.parseDouble(matcher.group(2));
        int result = number(matcher.group(3));

        assertThat(multiplier)
                .as("결론이 쓰는 배율은 peak 의 주문당 이벤트여야 한다 — §8.2 의 피크가 "
                        + "캠프 하루치 15,000 이고 peak 데이터셋이 그 규모다")
                .isCloseTo(measured(Dataset.PEAK).eventsPerOrder(), within(0.0001d));
        assertThat((double) result).as("%d × %s 의 반올림", rps, multiplier)
                .isCloseTo(Math.round(rps * multiplier), within(0.5d));

        String peakModel = section(read(REPO_ROOT.resolve("docs/DESIGN.md")),
                "### 8.2 피크 시나리오 모델", "\n### 8.3");
        assertThat(peakModel)
                .as("§8.2 의 피크 모델에 «%d rps» 가 없다 — 부하 모델이 바뀌었으면 이 기준선도 "
                        + "다시 환산해야 한다", rps)
                .contains(rps + " rps");
    }

    // --- 읽기 ----------------------------------------------------------------

    private static Map<String, Row> readRows() {
        Map<String, Row> rows = new LinkedHashMap<>();
        if (!Files.exists(DOCUMENT)) {
            return rows;                        // 전제 테스트가 말한다
        }
        for (String line : read(DOCUMENT).lines().toList()) {
            Matcher matcher = ROW.matcher(line);
            if (matcher.matches()) {
                rows.put(matcher.group(1), new Row(
                        number(matcher.group(2)), number(matcher.group(3)),
                        Double.parseDouble(matcher.group(4)),
                        number(matcher.group(5)), Double.parseDouble(matcher.group(6))));
            }
        }
        return rows;
    }

    private static int number(String cell) {
        return Integer.parseInt(cell.replace(",", ""));
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static String section(String text, String heading, String next) {
        int from = text.indexOf(heading);
        if (from < 0) {
            return "";
        }
        int to = text.indexOf(next, from);
        return to < 0 ? text.substring(from) : text.substring(from, to);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("문서를 읽지 못했습니다: " + path, e);
        }
    }

    private static Path locateRepoRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("docs").resolve("benchmarks"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("docs/benchmarks 를 찾지 못했습니다. 작업 디렉터리=" + current);
    }

}
