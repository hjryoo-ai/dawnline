package com.dawnline.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dawnline.common.Ids;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 고객 주문 API (DESIGN.md §5.1) — 실제 PostgreSQL 18, <strong>Redis 없음</strong>, 릴레이 꺼짐.
 *
 * <p>{@code PlaceOrderIT} 가 유스케이스까지를 보았다면 여기는 <em>HTTP 계층</em>을 본다 —
 * 상태 코드, 헤더, Problem Details 의 모양, 경로 버저닝, 커서. 그 넷은 유스케이스 테스트로는
 * 전혀 확인되지 않는다.
 */
@SpringBootTest(classes = OrderApplication.class)
@AutoConfigureMockMvc
@DisplayName("OrderApiIT — 고객 주문 API")
class OrderApiIT extends OrderIntegrationTestBase {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 이유는 {@code PlaceOrderIT} 와 같다 — Redis 없이도 성립해야 하고, 릴레이는 이 테스트의 대상이 아니다. */
    @DynamicPropertySource
    static void deadRedisAndNoRelay(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> "1");
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    @BeforeEach
    void clear() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM outbox_events").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM idempotency_keys").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM order_items").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM orders").executeUpdate();
        });
    }

    private static String body(UUID customerId, String tier) {
        return """
                {
                  "customerId": "%s",
                  "serviceTier": "%s",
                  "addressLine": "서울 강남구 테헤란로 1",
                  "postalCode": "06236",
                  "parcel": { "weightG": 1200, "volumeCm3": 8000, "requiresCold": false, "hazmat": false },
                  "items": [ { "sku": "SKU-1001", "qty": 2 }, { "sku": "SKU-2043", "qty": 1 } ]
                }
                """.formatted(customerId, tier);
    }

    private MvcResult place(String key, UUID customerId) throws Exception {
        return mockMvc.perform(post("/api/v1/orders")
                        .header(IDEMPOTENCY_KEY, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(customerId, "DAWN")))
                .andReturn();
    }

    private static String orderIdOf(MvcResult result) throws Exception {
        String json = result.getResponse().getContentAsString();
        return com.dawnline.messaging.json.EventJson.standard().readTree(json).get("orderId").asString();
    }

    @Test
    void 주문을_접수하면_201_과_Location_이_온다() throws Exception {
        UUID customerId = Ids.newId();

        mockMvc.perform(post("/api/v1/orders")
                        .header(IDEMPOTENCY_KEY, "api-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(customerId, "DAWN")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.serviceTier").value("DAWN"))
                .andExpect(jsonPath("$.promisedStart").exists())
                .andExpect(jsonPath("$.orderId").exists());
    }

    @Test
    void 같은_키로_다시_보내면_200_이고_Location_이_없다() throws Exception {
        // 201 과 200 을 나누는 것이 재생임을 알리는 신호다. Location 도 붙이지 않는다 —
        // 새로 만들어진 것이 없기 때문이다.
        UUID customerId = Ids.newId();
        MvcResult first = place("api-2", customerId);

        mockMvc.perform(post("/api/v1/orders")
                        .header(IDEMPOTENCY_KEY, "api-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(customerId, "DAWN")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.orderId").value(orderIdOf(first)));
    }

    @Test
    void 같은_키에_다른_본문이면_422_이고_Problem_Details_다() throws Exception {
        UUID customerId = Ids.newId();
        place("api-3", customerId);

        mockMvc.perform(post("/api/v1/orders")
                        .header(IDEMPOTENCY_KEY, "api-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(customerId, "SAME_DAY")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("https://dawnline.internal/problems/unprocessable-request"))
                .andExpect(jsonPath("$.code").value("unprocessable-request"))
                .andExpect(jsonPath("$.instance").value("/api/v1/orders"));
    }

    @Test
    void 멱등_키가_없으면_400_이고_본문이_있다() throws Exception {
        // 프레임워크가 만든 ProblemDetail 은 type 이 null 이다. 널 비교를 빠뜨리면 예외 처리기 안에서
        // NPE 가 나고 응답이 "본문 없는 400" 으로 조용히 나간다 — 실제로 그렇게 깨졌다.
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Ids.newId(), "DAWN")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("https://dawnline.internal/problems/validation-failed"))
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.instance").value("/api/v1/orders"));
    }

    @Test
    void 검증_실패는_어긋난_필드를_모두_돌려준다() throws Exception {
        // 하나씩 고치며 다시 보내게 하면 왕복이 필드 수만큼 늘어난다.
        String broken = """
                {
                  "customerId": "%s",
                  "serviceTier": "DAWN",
                  "addressLine": "",
                  "postalCode": "062",
                  "parcel": { "weightG": 1200, "volumeCm3": 8000, "requiresCold": false, "hazmat": false },
                  "items": []
                }
                """.formatted(Ids.newId());

        mockMvc.perform(post("/api/v1/orders")
                        .header(IDEMPOTENCY_KEY, "api-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(broken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[*].field",
                        org.hamcrest.Matchers.containsInAnyOrder("addressLine", "postalCode", "items")));
    }

    @Test
    void 검증_오류에_거부된_값을_담지_않는다() throws Exception {
        // 주소·연락처가 그대로 오류 응답과 로그에 실리는 흔한 경로다 (§9.3).
        String broken = """
                {
                  "customerId": "%s",
                  "serviceTier": "DAWN",
                  "addressLine": "서울 강남구 비밀아파트 101동 1203호",
                  "postalCode": "062",
                  "parcel": { "weightG": 1200, "volumeCm3": 8000, "requiresCold": false, "hazmat": false },
                  "items": [ { "sku": "SKU-1", "qty": 1 } ]
                }
                """.formatted(Ids.newId());

        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header(IDEMPOTENCY_KEY, "api-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(broken))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("비밀아파트").doesNotContain("062");
    }

    @Test
    void 주문을_조회할_수_있다() throws Exception {
        UUID customerId = Ids.newId();
        String orderId = orderIdOf(place("api-6", customerId));

        mockMvc.perform(get("/api/v1/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].lineNo").value(1))
                .andExpect(jsonPath("$.address.geohash7").exists());
    }

    @Test
    void 없는_주문은_404_다() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{id}", Ids.newId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void 주문_id_가_UUID_가_아니면_400_이다() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    void 취소하면_상태가_CANCELLED_가_된다() throws Exception {
        UUID customerId = Ids.newId();
        String orderId = orderIdOf(place("api-7", customerId));

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"고객 요청\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/v1/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void 사유_없이도_취소할_수_있다() throws Exception {
        String orderId = orderIdOf(place("api-8", Ids.newId()));

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void 이미_취소된_주문을_다시_취소하면_409_다() throws Exception {
        String orderId = orderIdOf(place("api-9", Ids.newId()));
        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId)).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("illegal-state-transition"))
                // 재시도해도 결과가 같은 409 다. Retry-After 를 붙이면 안 된다.
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    void 취소는_order_cancelled_를_outbox_에_남긴다() throws Exception {
        String orderId = orderIdOf(place("api-10", Ids.newId()));

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId)).andExpect(status().isOk());

        String eventTypes = new TransactionTemplate(transactionManager).execute(status ->
                (String) entityManager.createNativeQuery(
                        "SELECT string_agg(event_type, ',' ORDER BY created_at) FROM outbox_events")
                        .getSingleResult());
        assertThat(eventTypes).isEqualTo("order.placed,order.cancelled");
    }

    @Test
    void 목록은_커서로_이어서_읽는다() throws Exception {
        UUID customerId = Ids.newId();
        for (int i = 0; i < 5; i++) {
            place("api-list-" + i, customerId);
        }

        MvcResult firstPage = mockMvc.perform(get("/api/v1/orders")
                        .param("customerId", customerId.toString())
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").exists())
                // 목록에는 주소 문자열을 담지 않는다 (§9.3).
                .andExpect(jsonPath("$.orders[0].postalCode").value("06236"))
                .andReturn();

        String cursor = com.dawnline.messaging.json.EventJson.standard()
                .readTree(firstPage.getResponse().getContentAsString()).get("nextCursor").asString();

        mockMvc.perform(get("/api/v1/orders")
                        .param("customerId", customerId.toString())
                        .param("cursor", cursor)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(3))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void 목록_응답에_주소_문자열이_없다() throws Exception {
        UUID customerId = Ids.newId();
        place("api-11", customerId);

        MvcResult result = mockMvc.perform(get("/api/v1/orders").param("customerId", customerId.toString()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("테헤란로");
    }

    @Test
    void 상태_필터가_적용된다() throws Exception {
        UUID customerId = Ids.newId();
        String cancelled = orderIdOf(place("api-12", customerId));
        place("api-13", customerId);
        mockMvc.perform(post("/api/v1/orders/{id}/cancel", cancelled)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/orders")
                        .param("customerId", customerId.toString())
                        .param("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].orderId").value(cancelled));
    }

    @Test
    void 망가진_커서는_400_이고_내용을_되돌려_주지_않는다() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/orders")
                        .param("customerId", Ids.newId().toString())
                        .param("cursor", "!!!not-base64!!!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("not-base64");
    }

    @Test
    void limit_상한을_넘으면_400_이다() throws Exception {
        // 조용히 줄이면 클라이언트가 목록의 끝을 오판한다.
        mockMvc.perform(get("/api/v1/orders")
                        .param("customerId", Ids.newId().toString())
                        .param("limit", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"));
    }

    @Test
    void 지원하지_않는_버전은_404_가_아니라_400_이다() throws Exception {
        // 경로만으로 막으면 "그런 리소스 없음" 이 되는데, 사실은 "그 버전은 지원하지 않음" 이다.
        mockMvc.perform(get("/api/v2/orders").param("customerId", Ids.newId().toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 액추에이터는_버전_해석의_영향을_받지_않는다() throws Exception {
        // /actuator/health 의 두 번째 세그먼트는 health 다. 그것을 버전으로 파싱하면 헬스 체크가 깨지고,
        // 레디니스 프로브가 실패하면 배포가 멈춘다.
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
    }
}
