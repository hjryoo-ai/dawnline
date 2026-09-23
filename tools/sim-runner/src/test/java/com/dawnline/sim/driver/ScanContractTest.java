package com.dawnline.sim.driver;

import static com.dawnline.sim.driver.DriverFixtures.DEPARTURE;
import static com.dawnline.sim.driver.DriverFixtures.ROUTE;
import static com.dawnline.sim.driver.DriverFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 도구가 보내는 것과 {@code contracts/openapi/tracking-service.yaml} 이 약속한 것을 대조한다.
 *
 * <h2>왜 이 테스트가 있나</h2>
 * 이 도구는 tracking 의 코드를 쓰지 않는다(§5.6 — 도구는 REST 로만 붙는다). 그래서
 * {@link ScanType} 과 {@code HttpScanClient.ScanBody} 는 <strong>계약을 옮겨 적은 것</strong>이고,
 * 서로를 비추는 두 목록은 대조 없이는 갈라진다 — 갈라진 쪽은 <em>빈자리</em>라 눈에 띄지 않고,
 * 여기서는 「보내지 않은 필드」나 「서버가 모르는 값」이라는 조용한 형태로 나타난다
 * (CLAUDE.md 코딩 컨벤션).
 *
 * <p>대조는 <strong>빼는 방식</strong>이다 — 값을 열거하지 않고 양쪽을 전부 읽어 <em>집합으로</em>
 * 비교한다. 드는 방식이면 새 스캔 종류가 조용히 검사 밖에 남는다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ScanContractTest {

    /** 계약이 약속한 경로. 이 도구가 실제로 부르는 경로와 대조한다 (ADR-009 의 {@code v1} 포함). */
    private static final String SCAN_PATH = "/api/v1/routes/{routeId}/stops/{stopSeq}/events";

    private static final Map<String, Object> CONTRACT = loadContract();

    @Test
    void 계약에는_스캔_경로가_하나_있다() {
        assertThat(paths()).containsOnlyKeys(SCAN_PATH);
    }

    @Test
    void 도구가_부르는_경로가_계약의_경로다() throws IOException {
        // 문자열 비교가 아니라 실제로 나간 요청과 비교한다 — PATH 상수를 고쳐 놓고
        // 상수끼리 비교하면 언제나 초록이다.
        try (LocalScanServer server = LocalScanServer.alwaysAnswering(200, "{}")) {
            server.client().report(ROUTE, new ScanCall(7, List.of(order(1)), ScanType.ARRIVED,
                    DEPARTURE, null, null, null));

            String expected = SCAN_PATH.replace("{routeId}", ROUTE.toString()).replace("{stopSeq}", "7");
            assertThat(server.received().getFirst().path()).isEqualTo(expected);
        }
    }

    @Test
    void 스캔_종류는_계약의_열거와_같다() {
        assertThat(EnumSet.allOf(ScanType.class)).extracting(Enum::name)
                .containsExactlyInAnyOrderElementsOf(enumOf("ScanRequest", "type"));
    }

    @Test
    void 보내는_본문_필드는_계약의_ScanRequest_와_같다() {
        List<String> sent = Arrays.stream(HttpScanClient.ScanBody.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(sent).containsExactlyInAnyOrderElementsOf(properties("ScanRequest").keySet());
    }

    @Test
    void 도구가_읽는_응답_필드가_계약에_있다() {
        // outcome 값 자체는 도구가 알지 않는다 — 받은 문자열을 그대로 센다(DriverTally).
        // 그래서 여기서 확인하는 것은 "어디를 읽는가" 이지 "무엇이 오는가" 가 아니다.
        assertThat(properties("ScanResult")).containsKey("orders");
        assertThat(properties("OrderScan")).containsKey("outcome");
    }

    @Test
    void 응답_404_는_재시도해도_되는_것으로_문서화되어_있다() {
        // DriverTrip 의 재시도는 이 문장에 기대고 있다. 문장이 사라지면 재시도의 근거도 사라진다.
        @SuppressWarnings("unchecked")
        Map<String, Object> responses = (Map<String, Object>)
                ((Map<String, Object>) ((Map<String, Object>) paths().get(SCAN_PATH)).get("post"))
                        .get("responses");
        @SuppressWarnings("unchecked")
        String description = String.valueOf(((Map<String, Object>) responses.get("404")).get("description"));

        assertThat(description).contains("다시 보내도 된다");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paths() {
        return (Map<String, Object>) CONTRACT.get("paths");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(String schema) {
        Map<String, Object> components = (Map<String, Object>) CONTRACT.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> found = (Map<String, Object>) schemas.get(schema);
        assertThat(found).as("계약에 %s 스키마가 있어야 한다", schema).isNotNull();
        return (Map<String, Object>) found.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static List<String> enumOf(String schema, String property) {
        Map<String, Object> field = (Map<String, Object>) properties(schema).get(property);
        return (List<String>) field.get("enum");
    }

    /**
     * 저장소의 {@code contracts/openapi/tracking-service.yaml} 을 읽는다.
     *
     * <p>Gradle 은 테스트의 작업 디렉터리를 모듈 디렉터리로 잡는다. 상대 경로를 하드코딩하면
     * 모듈 위치가 바뀔 때 조용히 깨지므로 위로 올라가며 찾는다 ({@code EventContracts} 와 같은 방식).
     */
    private static Map<String, Object> loadContract() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path contract = candidate.resolve("contracts").resolve("openapi").resolve("tracking-service.yaml");
            if (Files.isRegularFile(contract)) {
                try (InputStream in = Files.newInputStream(contract)) {
                    return new Yaml().load(in);
                } catch (IOException exception) {
                    throw new IllegalStateException("계약을 읽지 못했습니다: " + contract, exception);
                }
            }
        }
        throw new IllegalStateException(
                "contracts/openapi/tracking-service.yaml 을 찾지 못했습니다. 작업 디렉터리=%s".formatted(current));
    }
}
