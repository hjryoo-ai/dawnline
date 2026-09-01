package com.dawnline.messaging.contract;

import com.dawnline.messaging.json.EventJson;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * {@code contracts/events/*.schema.json} 으로 이벤트를 검증하는 테스트 유틸
 * (CLAUDE.md 불변규칙 8, DESIGN.md §4.7, contracts/events/README.md §3).
 *
 * <p>testFixtures 로 제공하므로 서비스 모듈이
 * {@code testImplementation(testFixtures(project(":libs:messaging")))} 로 그대로 쓴다.
 * 발행자 테스트는 {@link #validateRecord(String)} 하나로 봉투와 페이로드를 함께 검증할 수 있다.
 *
 * <h2>검증기 설정에서 중요한 것 하나</h2>
 * JSON Schema 2020-12 에서 {@code format} 은 <strong>기본이 주석</strong>이다. 켜지 않으면
 * {@code "placedAt": "어제"} 도 통과한다. 그래서 {@code formatAssertionsEnabled(true)} 를 반드시 켠다
 * (contracts/events/README.md 3절의 경고).
 *
 * <p>networknt 3.0.7 의 API 는 1.x 와 이름이 전부 다르다({@code SchemaRegistry}·{@code Schema}·{@code Error}).
 * 그리고 이 라이브러리가 쓰는 {@code JsonNode} 는 <strong>Jackson 3</strong>({@code tools.jackson.databind})이라
 * Boot 4 기본 Jackson 과 같은 계열이다. 변환이 필요 없다.
 */
public final class EventContracts {

    /** contracts/events/README.md 1절의 예시 파일명 규칙. */
    private static final Pattern EXAMPLE_FILE = Pattern.compile(
            "^(?<eventType>[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+)"
                    + "\\.v(?<major>\\d+)(?:\\.(?<variant>[a-z0-9-]+))?\\.example\\.json$");

    private static final String ENVELOPE_SCHEMA = "envelope.v1.schema.json";

    private final Path contractsDirectory;
    private final SchemaRegistry registry;
    private final EventJson json;
    private final Map<String, Schema> schemaCache = new ConcurrentHashMap<>();

    private EventContracts(Path contractsDirectory) {
        this.contractsDirectory = Objects.requireNonNull(contractsDirectory, "contractsDirectory");
        SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                // 이걸 끄면 date-time·uuid 가 검사되지 않는다. 계약 테스트의 존재 이유가 절반 사라진다.
                .formatAssertionsEnabled(Boolean.TRUE)
                .build();
        this.registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaRegistryConfig(config));
        this.json = EventJson.standard();
    }

    /** 저장소의 {@code contracts/events} 를 찾아 만든다. */
    public static EventContracts load() {
        return new EventContracts(locateContractsDirectory());
    }

    /**
     * 명시한 디렉터리로 만든다.
     *
     * @param contractsDirectory {@code contracts/events} 디렉터리
     */
    public static EventContracts load(Path contractsDirectory) {
        return new EventContracts(contractsDirectory);
    }

    /**
     * Kafka 레코드 value 전체를 검증한다 — 봉투 + 페이로드.
     *
     * @param recordValue 봉투로 감싼 JSON
     * @throws AssertionError 계약 위반이 있을 때
     */
    public void validateRecord(String recordValue) {
        validateRecord(json.readTree(recordValue));
    }

    /**
     * @param envelope 봉투 트리
     * @throws AssertionError 계약 위반이 있을 때
     */
    public void validateRecord(JsonNode envelope) {
        Objects.requireNonNull(envelope, "envelope");
        validateEnvelope(envelope);
        validatePayload(envelope.get("eventType").asString(), envelope.get("schemaVersion").intValue(),
                envelope.get("payload"));
    }

    /**
     * 봉투 구조만 검증한다.
     *
     * @param envelope 봉투 트리
     */
    public void validateEnvelope(JsonNode envelope) {
        assertValid(ENVELOPE_SCHEMA, schema(ENVELOPE_SCHEMA), envelope);
    }

    /**
     * 페이로드를 {@code <eventType>.v<major>.schema.json} 으로 검증한다.
     *
     * @param eventType     이벤트 타입
     * @param schemaVersion 스키마 major 버전
     * @param payload       페이로드 트리
     */
    public void validatePayload(String eventType, int schemaVersion, JsonNode payload) {
        String fileName = payloadSchemaFileName(eventType, schemaVersion);
        assertValid(fileName, schema(fileName), payload);
    }

    /** {@code contracts/events/examples} 의 모든 예시 파일. 파일명 오름차순. */
    public List<Path> examples() {
        Path examplesDirectory = contractsDirectory.resolve("examples");
        try (Stream<Path> files = Files.list(examplesDirectory)) {
            return files.filter(path -> EXAMPLE_FILE.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("예시 디렉터리를 읽을 수 없습니다: " + examplesDirectory, e);
        }
    }

    /**
     * 예시 파일명에서 기대되는 {@code (eventType, major)} 를 뽑는다.
     *
     * @param example 예시 파일 경로
     * @return 파일명이 약속하는 이벤트 타입과 major 버전
     */
    public static ExampleName parseExampleName(Path example) {
        String fileName = example.getFileName().toString();
        Matcher matcher = EXAMPLE_FILE.matcher(fileName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "예시 파일명이 규칙에 맞지 않습니다(contracts/events/README.md 1절): " + fileName);
        }
        return new ExampleName(matcher.group("eventType"), Integer.parseInt(matcher.group("major")),
                matcher.group("variant"));
    }

    /**
     * 파일 내용을 트리로 읽는다.
     *
     * @param file 읽을 파일
     */
    public JsonNode readTree(Path file) {
        try {
            return json.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("파일을 읽을 수 없습니다: " + file, e);
        }
    }

    /** 이 유틸이 쓰는 이벤트 전용 JSON 코덱. 예시 역직렬화 테스트에서 같은 설정을 쓰라고 공개한다. */
    public EventJson json() {
        return json;
    }

    /** 사용 중인 {@code contracts/events} 디렉터리. */
    public Path contractsDirectory() {
        return contractsDirectory;
    }

    /** {@code <eventType>.v<major>.schema.json} */
    public static String payloadSchemaFileName(String eventType, int schemaVersion) {
        return "%s.v%d.schema.json".formatted(eventType, schemaVersion);
    }

    private Schema schema(String fileName) {
        return schemaCache.computeIfAbsent(fileName, this::loadSchema);
    }

    private Schema loadSchema(String fileName) {
        Path file = contractsDirectory.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(
                    "계약 스키마가 없습니다: %s%n새 이벤트는 코드보다 스키마가 먼저다(CLAUDE.md 불변규칙 8)."
                            .formatted(file));
        }
        try (InputStream in = Files.newInputStream(file)) {
            return registry.getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException("스키마를 읽을 수 없습니다: " + file, e);
        }
    }

    private static void assertValid(String schemaName, Schema schema, JsonNode instance) {
        List<Error> errors = schema.validate(instance);
        if (errors.isEmpty()) {
            return;
        }
        List<String> messages = new ArrayList<>(errors.size());
        for (Error error : errors) {
            messages.add("  - %s: %s".formatted(error.getInstanceLocation(), error.getMessage()));
        }
        throw new AssertionError("%s 검증 실패 (%d건)%n%s"
                .formatted(schemaName, errors.size(), String.join(System.lineSeparator(), messages)));
    }

    /**
     * 작업 디렉터리에서 위로 올라가며 {@code contracts/events} 를 찾는다.
     *
     * <p>Gradle 은 테스트의 작업 디렉터리를 <em>모듈</em> 디렉터리로 잡는다(예: {@code libs/messaging}).
     * 상대 경로 {@code ../../contracts/events} 를 하드코딩하면 모듈 위치가 바뀔 때 조용히 깨지므로,
     * 저장소 루트를 찾아 올라간다.
     */
    private static Path locateContractsDirectory() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path events = candidate.resolve("contracts").resolve("events");
            if (Files.isDirectory(events)) {
                return events;
            }
        }
        throw new IllegalStateException(
                "contracts/events 를 찾지 못했습니다. 작업 디렉터리=%s".formatted(current));
    }

    /**
     * 예시 파일명이 약속하는 값.
     *
     * @param eventType     이벤트 타입
     * @param schemaVersion major 버전
     * @param variant       분기 이름 (없으면 {@code null})
     */
    public record ExampleName(String eventType, int schemaVersion, @Nullable String variant) {
    }
}
