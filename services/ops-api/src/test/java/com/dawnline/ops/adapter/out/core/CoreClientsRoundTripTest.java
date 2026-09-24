package com.dawnline.ops.adapter.out.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dawnline.ops.adapter.out.core.dispatch.api.PlanControllerApi;
import com.dawnline.ops.adapter.out.core.dispatch.api.RouteControllerApi;
import com.dawnline.ops.adapter.out.core.dispatch.model.ReassignRequest;
import com.dawnline.ops.adapter.out.core.order.api.OrderControllerApi;
import com.dawnline.ops.adapter.out.core.order.model.CancelOrderRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 생성된 위임 클라이언트가 Jackson 3 로 계약을 잃지 않고 왕복하는가 — ADR-052 채택 기준 4.
 *
 * <h2>무엇을 보나</h2>
 * <ul>
 *   <li><b>모델 전부</b> — 두 계약의 {@code components.schemas} 를 <em>전부</em> 읽고(열거하지 않는다),
 *       스키마대로 모든 칸을 채운 JSON 을 생성 모델로 읽었다가 다시 써서 같은 트리가 나오는지 본다.
 *       칸 하나가 이름이 어긋나거나 타입이 맞지 않으면 그 칸이 사라지거나 예외가 난다.</li>
 *   <li><b>위임하는 세 연산</b> — 같은 일을 {@code RestClient} + 프록시 경로를 거쳐서 한다: 요청
 *       본문의 키가 계약 스키마의 속성 이름과 같고, 응답 본문이 값을 잃지 않는다.</li>
 * </ul>
 *
 * <p>{@link JsonMapper} 와 {@link RestClient.Builder} 는 손으로 만들지 않고 Boot 4 의 자동 구성에서
 * 받는다 — 앱이 실제로 쓰는 직렬화기로 봐야 기준이 말하는 것을 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("CoreClientsRoundTripTest — 생성 클라이언트의 Jackson 3 왕복 (ADR-052 기준 4)")
class CoreClientsRoundTripTest {

    private static final Path CONTRACTS = locateContracts();

    /** 생성 태스크가 쓰는 계약과 같은 짝이다 — {@code services/ops-api/build.gradle.kts} 의 {@code coreContracts}. */
    private static final Map<String, String> CORES = Map.of(
            "dispatch", "dispatch-service.yaml",
            "order", "order-service.yaml");

    private static final UUID ID = UUID.fromString("0199a000-0000-7000-8000-000000000001");

    private static JsonMapper mapper;
    private static RestClient.Builder builder;

    @BeforeAll
    static void bootAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class, RestClientAutoConfiguration.class))
                .run(context -> {
                    mapper = context.getBean(JsonMapper.class);
                    builder = context.getBean(RestClient.Builder.class);
                });
        assertThat(mapper).as("Boot 4 의 JsonMapper 를 받았다 — 없으면 이 검사는 다른 직렬화기를 본다").isNotNull();
    }

    static Stream<Schema> schemas() {
        List<Schema> all = new ArrayList<>();
        CORES.forEach((core, file) -> schemasOf(file).keySet()
                .forEach(name -> all.add(new Schema(core, file, name))));
        return all.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemas")
    void 계약의_모든_모델이_칸을_잃지_않고_왕복한다(Schema schema) throws Exception {
        Map<String, Object> components = schemasOf(schema.file());
        JsonNode sample = mapper.valueToTree(sample(components.get(schema.name()), components));
        Class<?> model = Class.forName("com.dawnline.ops.adapter.out.core." + schema.core() + ".model." + schema.name());

        Object read = mapper.treeToValue(sample, model);

        assertThat(mapper.<JsonNode>valueToTree(read)).as("%s — 스키마대로 채운 JSON", schema).isEqualTo(sample);
    }

    @Test
    void 재계획은_계약의_경로와_쿼리로_나가고_응답을_잃지_않는다() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Map<String, Object> dispatch = schemasOf("dispatch-service.yaml");
        JsonNode reply = mapper.valueToTree(sample(dispatch.get("RunPlanResponse"), dispatch));
        server.expect(requestTo("http://core/api/v1/plans/" + ID + "/run?campId=" + ID + "&mode=FAST"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(reply.toString(), MediaType.APPLICATION_JSON));

        var response = proxy(PlanControllerApi.class).run(ID, ID, null, "FAST");

        assertThat(mapper.<JsonNode>valueToTree(response.getBody())).isEqualTo(reply);
        server.verify();
    }

    @Test
    void 재배정의_요청_키는_계약의_속성_이름이고_응답을_잃지_않는다() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Map<String, Object> dispatch = schemasOf("dispatch-service.yaml");
        JsonNode reply = mapper.valueToTree(sample(dispatch.get("Result"), dispatch));
        List<JsonNode> sent = new ArrayList<>();
        server.expect(requestTo("http://core/api/v1/routes/" + ID + "/stops/" + ID + "/reassign"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> sent.add(mapper.readTree(((MockClientHttpRequest) request).getBodyAsString())))
                .andRespond(withSuccess(reply.toString(), MediaType.APPLICATION_JSON));

        var response = proxy(RouteControllerApi.class).reassign(ID, ID, new ReassignRequest(ID));

        assertThat(fieldNames(sent.getFirst())).isEqualTo(propertyNames(dispatch, "ReassignRequest"));
        assertThat(mapper.<JsonNode>valueToTree(response.getBody())).isEqualTo(reply);
        server.verify();
    }

    @Test
    void 취소의_요청_키는_계약의_속성_이름이고_응답을_잃지_않는다() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Map<String, Object> order = schemasOf("order-service.yaml");
        JsonNode reply = mapper.valueToTree(sample(order.get("OrderView"), order));
        List<JsonNode> sent = new ArrayList<>();
        server.expect(requestTo("http://core/api/v1/orders/" + ID + "/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> sent.add(mapper.readTree(((MockClientHttpRequest) request).getBodyAsString())))
                .andRespond(withSuccess(reply.toString(), MediaType.APPLICATION_JSON));

        var response = proxy(OrderControllerApi.class).cancel(ID, new CancelOrderRequest().reason("고객 요청"));

        assertThat(fieldNames(sent.getFirst())).isEqualTo(propertyNames(order, "CancelOrderRequest"));
        assertThat(mapper.<JsonNode>valueToTree(response.getBody())).isEqualTo(reply);
        server.verify();
    }

    private static <T> T proxy(Class<T> api) {
        RestClient client = builder.baseUrl("http://core").build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build().createClient(api);
    }

    // --- 스키마에서 표본을 만든다 --------------------------------------------------------------

    /** 스키마가 말하는 모든 칸을 채운 값. 모르는 모양이면 멈춘다 — 조용히 건너뛰면 그 칸은 검사 밖이다. */
    @SuppressWarnings("unchecked")
    private static @Nullable Object sample(Object node, Map<String, Object> components) {
        Map<String, Object> schema = (Map<String, Object>) node;
        if (schema.containsKey("$ref")) {
            String ref = (String) schema.get("$ref");
            return sample(components.get(ref.substring(ref.lastIndexOf('/') + 1)), components);
        }
        if (schema.containsKey("enum")) {
            return ((List<Object>) schema.get("enum")).getFirst();
        }
        String type = String.valueOf(schema.get("type"));
        String format = String.valueOf(schema.get("format"));
        return switch (type) {
            case "string" -> switch (format) {
                case "uuid" -> ID.toString();
                case "date-time" -> "2026-09-24T01:02:03Z";
                case "time-local" -> "01:02:03";
                case "uri" -> "https://dawnline.internal/problems/sample";
                case "null" -> "표본";
                default -> throw new IllegalStateException("모르는 문자열 형식: " + format);
            };
            // 두 갈래를 Object 로 적는다 — 삼항식은 7 을 long 으로 승격시켜 int32 칸을 LongNode 로 만든다.
            case "integer" -> "int64".equals(format) ? (Object) 7_000_000_000L : (Object) 7;
            case "number" -> 1.5;
            case "boolean" -> true;
            case "array" -> List.of(sample(schema.get("items"), components));
            case "object" -> {
                Map<String, Object> value = new LinkedHashMap<>();
                Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
                properties.forEach((name, property) -> value.put(name, sample(property, components)));
                if (properties.isEmpty() && schema.containsKey("additionalProperties")) {
                    value.put("key", "value");
                }
                yield value;
            }
            default -> throw new IllegalStateException("모르는 스키마: " + schema);
        };
    }

    @SuppressWarnings("unchecked")
    private static Set<String> propertyNames(Map<String, Object> components, String name) {
        Map<String, Object> schema = (Map<String, Object>) components.get(name);
        return new TreeSet<>(((Map<String, Object>) schema.get("properties")).keySet());
    }

    private static Set<String> fieldNames(JsonNode node) {
        return new TreeSet<>(node.propertyNames());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemasOf(String file) {
        try (InputStream in = Files.newInputStream(CONTRACTS.resolve(file))) {
            Map<String, Object> root = new Yaml().load(in);
            return (Map<String, Object>) ((Map<String, Object>) root.get("components")).get("schemas");
        } catch (IOException e) {
            throw new IllegalStateException(file, e);
        }
    }

    private static Path locateContracts() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("contracts/openapi");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("contracts/openapi 를 찾지 못했다");
    }

    record Schema(String core, String file, String name) {
        @Override
        public String toString() {
            return core + " " + name;
        }
    }
}
