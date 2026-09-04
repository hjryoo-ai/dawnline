package com.dawnline.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dawnline.common.Ids;
import com.redis.testcontainers.RedisContainer;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 레이트 리밋의 HTTP 계약 (DESIGN.md §7.2, §8.3) — 실제 Redis 8.
 *
 * <p>{@code RateLimitIT} 가 버킷의 산수를 본다면 여기는 <em>응답</em>을 본다 — 429 인지,
 * Problem Details 인지, {@code Retry-After} 가 붙는지.
 *
 * <p>용량을 3으로 낮춰 쓴다. 기본값 60으로 검증하려면 61번의 주문 접수가 필요한데, 그것은 이
 * 테스트가 보려는 것과 무관한 시간이다. 60이라는 값 자체는 {@code OrderScheduledDefaultsTest} 류의
 * 설정 테스트와 k6 {@code rate-limit.js} 가 확인한다.
 */
@SpringBootTest(classes = OrderApplication.class)
@AutoConfigureMockMvc
@DisplayName("RateLimitApiIT — 429 와 Retry-After")
class RateLimitApiIT extends OrderIntegrationTestBase {

    private static final int CAPACITY = 3;
    private static final RedisContainer REDIS = new RedisContainer("redis:8.8.2");

    static {
        REDIS.start();
    }

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void liveRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getRedisHost);
        registry.add("spring.data.redis.port", REDIS::getRedisPort);
        registry.add("dawnline.order.rate-limit.capacity", () -> CAPACITY);
        // 리필이 테스트 도중 끼어들지 않게 느리게 둔다. 리필 자체는 RateLimitIT 가 본다.
        registry.add("dawnline.order.rate-limit.refill-per-second", () -> 1);
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    private static String body(UUID customerId) {
        return """
                {
                  "customerId": "%s",
                  "serviceTier": "DAWN",
                  "addressLine": "서울 강남구 테헤란로 1",
                  "postalCode": "06236",
                  "parcel": { "weightG": 1200, "volumeCm3": 8000, "requiresCold": false, "hazmat": false },
                  "items": [ { "sku": "SKU-1001", "qty": 1 } ]
                }
                """.formatted(customerId);
    }

    private int place(UUID customerId, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(customerId)))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void 용량을_넘으면_429_와_Retry_After_가_온다() throws Exception {
        UUID customer = Ids.newId();
        for (int i = 0; i < CAPACITY; i++) {
            assertThat(place(customer, "rl-" + customer + "-" + i)).isEqualTo(201);
        }

        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", "rl-" + customer + "-over")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(customer)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.type").value("https://dawnline.internal/problems/rate-limited"))
                .andExpect(jsonPath("$.code").value("rate-limited"))
                .andExpect(jsonPath("$.instance").value("/api/v1/orders"));
    }

    @Test
    void 막힌_요청은_주문을_만들지_않는다() throws Exception {
        // 레이트 리밋이 쓰기 경로보다 앞에 서지 않으면 429 를 주면서 주문은 저장될 수 있다.
        UUID customer = Ids.newId();
        for (int i = 0; i < CAPACITY; i++) {
            place(customer, "guard-" + customer + "-" + i);
        }

        assertThat(place(customer, "guard-" + customer + "-over")).isEqualTo(429);

        // 같은 멱등 키로 다시 보내도 그 주문은 없다 — 429 때 아무것도 쓰이지 않았다는 뜻이다.
        // (토큰이 없으므로 이번에도 429 다.)
        assertThat(place(customer, "guard-" + customer + "-over")).isEqualTo(429);
    }

    @Test
    void 고객마다_따로_센다() throws Exception {
        UUID heavy = Ids.newId();
        UUID quiet = Ids.newId();
        for (int i = 0; i <= CAPACITY; i++) {
            place(heavy, "multi-" + heavy + "-" + i);
        }

        assertThat(place(heavy, "multi-" + heavy + "-more")).isEqualTo(429);
        assertThat(place(quiet, "multi-" + quiet + "-0")).isEqualTo(201);
    }

    @Test
    void 멱등_재요청도_토큰을_쓴다() throws Exception {
        // §7.2 — 구분하면 복잡도만 늘고, 이 속도에서 재시도 몇 번은 문제가 되지 않는다.
        UUID customer = Ids.newId();
        String key = "replay-" + customer;
        assertThat(place(customer, key)).isEqualTo(201);
        assertThat(place(customer, key)).as("재생이지만 토큰은 쓴다").isEqualTo(200);
        assertThat(place(customer, key)).isEqualTo(200);

        assertThat(place(customer, key)).as("네 번째는 토큰이 없다").isEqualTo(429);
    }
}
