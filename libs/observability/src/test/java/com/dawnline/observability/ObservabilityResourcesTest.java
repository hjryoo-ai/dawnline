package com.dawnline.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/**
 * 라이브러리가 제공하는 설정 리소스가 실제로 로드 가능한 상태인지 지키는 테스트.
 *
 * <p>logback XML 은 형식이 깨져도 애플리케이션이 죽지 않고 "설정만 조용히 무시"되기 때문에
 * 운영에서 발견하기 나쁘다. 실제로 이 조각을 처음 만들 때 XML 주석 안에 중첩 주석이 들어가
 * {@code The string "--" is not permitted within comments} 로 include 가 통째로 실패한 적이
 * 있다. 그 부류를 빌드 단계에서 잡는다.
 */
class ObservabilityResourcesTest {

    private static final String LOGBACK = "com/dawnline/observability/logback-dawnline.xml";
    private static final String DEFAULTS = "com/dawnline/observability/observability-defaults.yml";
    private static final String IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @Test
    void logback조각_잘형성된XML이다() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // 외부 엔티티는 읽지 않는다(XXE 방지).
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = factory.newDocumentBuilder();

        try (InputStream in = resource(LOGBACK)) {
            Document document = builder.parse(in);
            assertThat(document.getDocumentElement().getTagName()).isEqualTo("included");
        }
    }

    @Test
    void logback조각_Boot내장인코더를쓰고자체인코더를만들지않는다() throws Exception {
        String xml = read(LOGBACK);
        assertThat(xml).contains("org.springframework.boot.logging.logback.StructuredLogEncoder");
        // 파일 어펜더는 두지 않는다 — 컨테이너에서는 stdout 이 로그 파이프다.
        assertThat(xml).doesNotContain("RollingFileAppender");
    }

    @Test
    void 기본프로퍼티조각_Boot4_1의최신프로퍼티경로를쓴다() throws Exception {
        // 주석에는 "쓰지 말 것" 설명으로 옛 이름이 등장하므로 주석을 걷어내고 본다.
        List<String> lines = configLines(read(DEFAULTS));

        // Boot 4.1 경로: management.opentelemetry.tracing.export.otlp.*
        assertThat(lines).contains("  opentelemetry:");
        // Boot 3 경로 management.otlp.tracing.* 는 4.1 에서 deprecation level=error 다.
        assertThat(lines).doesNotContain("  otlp:");
    }

    @Test
    void 기본프로퍼티조각_Kafka관측과구조화로그를켠다() throws Exception {
        String yaml = String.join("\n", configLines(read(DEFAULTS)));

        // 둘 다 Boot 4.1 기본값이 false 라서 명시적으로 켜야 traceparent 가 Kafka 헤더에 실린다.
        assertThat(yaml).contains("observation-enabled: true");
        // ECS 는 MDC 의 service 키와 충돌해 로그가 유실된다. logstash 여야 한다.
        assertThat(yaml).contains("console: \"logstash\"");
        // §9.1 histogram 메트릭 이름을 그대로 써야 버킷이 붙는다.
        assertThat(yaml).contains(DawnlineMetrics.PLAN_DURATION_SECONDS);
    }

    @Test
    void 자동구성_imports파일에등록되어있다() throws Exception {
        // 클래스패스에는 같은 이름의 imports 파일이 여러 개 있다(Boot 자신도 갖고 있다).
        // Boot 은 전부를 모아 병합하므로 테스트도 전부를 훑어야 한다.
        List<String> registered = new ArrayList<>();
        Enumeration<URL> urls = ObservabilityResourcesTest.class.getClassLoader().getResources(IMPORTS);
        while (urls.hasMoreElements()) {
            try (InputStream in = urls.nextElement().openStream()) {
                new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(registered::add);
            }
        }

        assertThat(registered)
                .contains("com.dawnline.observability.config.ObservabilityAutoConfiguration");
    }

    /** YAML 에서 주석과 빈 줄을 걷어낸 설정 줄만 남긴다. */
    private static List<String> configLines(String yaml) {
        return yaml.lines()
                .map(line -> {
                    int hash = line.indexOf('#');
                    return (hash < 0) ? line : line.substring(0, hash);
                })
                .map(line -> line.stripTrailing())
                .filter(line -> !line.isBlank())
                .toList();
    }

    private static InputStream resource(String path) {
        InputStream in = ObservabilityResourcesTest.class.getClassLoader().getResourceAsStream(path);
        assertThat(in).as("클래스패스 리소스 없음: %s", path).isNotNull();
        return in;
    }

    private static String read(String path) throws Exception {
        try (InputStream in = resource(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
