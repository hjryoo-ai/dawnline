package com.dawnline.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dawnline.common.Ids;
import com.dawnline.messaging.config.MessagingAutoConfiguration;
import com.dawnline.observability.DawnlineMetrics;
import com.redis.testcontainers.RedisContainer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
 *
 * <h2>시계를 멈춰 둔다 — 리필이 <em>구조적으로</em> 0 이어야 한다</h2>
 * 버킷은 {@code (지금 − 마지막 갱신) × 초당 리필} 로 토큰을 되돌린다. 실제 시계로 돌면 그 값이
 * <strong>러너 속도</strong>에 달리고, 요청 사이에 1초가 지나면 비운 버킷에 토큰 하나가 돌아와
 * <strong>429 가 201 로 바뀐다</strong> — 2026-09-23 CI 에서 {@code 고객마다_따로_센다} 가 그렇게
 * 깨졌다(주문 INSERT 세 건이 그 창 안에 있다). {@code refill-per-second} 는 이미 설정이 허용하는
 * 최솟값 1 이라 값으로는 더 줄일 수 없다.
 *
 * <p>이음매는 처음부터 있었다: 버킷 Lua 는 시각을 <em>인자로</em> 받고 그 이유를 스스로
 * 「리필을 테스트로 재현할 수 없기 때문」이라고 적어 두었다(불변규칙 12,
 * {@code rate-limit-token-bucket.lua}). 이 클래스만 그 이음매를 쓰지 않고 있었다. 고정 시계를
 * 빈으로 넣으면 요청 사이에 시간이 흐르지 않고, 리필은 러너가 아무리 느려도 0 이다.
 * {@code MessagingAutoConfiguration.dawnlineClock} 이 {@code @ConditionalOnMissingBean} 이라
 * 이쪽이 이긴다 — 그 javadoc 이 「테스트의 고정 시계」를 그 용도로 적어 두었다.
 */
@SpringBootTest(classes = OrderApplication.class)
@Import(RateLimitApiIT.FrozenClock.class)
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

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    @Autowired
    private Clock clock;

    /**
     * 멈춘 시계. 저장 정밀도(마이크로초)로 자른 «지금» 에서 멈춘다 — 리터럴을 쓰지 않는 이유는
     * 이 값이 컷오프·약속창 계산에도 들어가기 때문이고(CLAUDE.md), 자르는 이유는
     * {@code MessagingAutoConfiguration.dawnlineClock} 의 javadoc 과 같다.
     */
    @TestConfiguration
    static class FrozenClock {

        @Bean
        Clock clock() {
            return Clock.fixed(MessagingAutoConfiguration.storagePrecisionClock().instant(),
                    ZoneOffset.UTC);
        }
    }

    /**
     * <strong>전제: 레이트 리밋이 켜져 있다.</strong>
     *
     * <p>Redis 장애·지연이면 레이트 리밋은 fail-open 으로 통과시킨다(§7.2). 그 상태에서 이
     * 클래스의 어설션은 "429 가 안 온다" 로 <em>실패</em>하는데, 실패 메시지는 레이트 리밋이
     * 잘못된 것처럼 보인다 — 실제로는 검사 대상이 꺼져 있던 것이다.
     *
     * <p>그래서 매 테스트 뒤 우회가 없었는지 확인한다. CLAUDE.md 의 「폴백 테스트는 전제를 첫
     * 어설션으로 스스로 말한다」와 같은 규칙이고, 방향만 반대다 — 저쪽은 <em>폴백이 도는지</em>,
     * 이쪽은 <em>폴백이 돌지 않는지</em>를 말한다.
     */
    @org.junit.jupiter.api.AfterEach
    void 전제_레이트_리밋이_우회되지_않았다() {
        io.micrometer.core.instrument.Counter bypassed = meters
                .find(DawnlineMetrics.RATE_LIMIT_DECISIONS.meterName()).tag("outcome", "bypassed").counter();
        if (bypassed != null) {
            org.assertj.core.api.Assertions.assertThat(bypassed.count())
                    .as("Redis 지연으로 레이트 리밋이 fail-open 되면 이 클래스가 검사하려는 것이 사라진다")
                    .isZero();
        }
    }

    @DynamicPropertySource
    static void liveRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getRedisHost);
        registry.add("spring.data.redis.port", REDIS::getRedisPort);
        registry.add("dawnline.order.rate-limit.capacity", () -> CAPACITY);
        // 설정이 허용하는 가장 느린 리필이다. 다만 **이 값이 리필을 막는 것은 아니다** —
        // 막는 것은 멈춘 시계이고(위 javadoc), 이 줄은 그 위에 얹힌 여유일 뿐이다.
        // 리필 자체의 산수는 RateLimitIT 가 본다.
        registry.add("dawnline.order.rate-limit.refill-per-second", () -> 1);
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        // Redis 명령 타임아웃을 넉넉하게 준다. 50 ms 는 **운영 핫패스의 SLO 예산**이고(§7.2),
        // 이 테스트가 보는 것은 그 예산이 아니라 레이트 리밋의 *의미*(429·Retry-After·토큰 소모)다.
        //
        // 느린 러너에서는 그 예산이 먼저 걸려 차단기가 열리고, 그러면 레이트 리밋이 fail-open 으로
        // 통과시켜 429 대신 201 이 온다 — 실제로 CI 에서 그렇게 깨졌다. 테스트가 검사하려던 것이
        // 사라진 채 실패하는 형태이므로, 예산이 아니라 의미를 보도록 늘린다.
        // 예산 자체가 지켜지는지는 k6 실측(docs/benchmarks/phase1-orders-k6.md)이 본다.
        registry.add("dawnline.order.redis.command-timeout-ms", () -> 2000);
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
    void 전제_시계가_멈춰_있다() throws Exception {
        // 이 클래스의 모든 어설션이 「리필이 끼어들지 않는다」에 기댄다. 전제가 조용히 무너지면
        // (고정 시계 빈이 사라지면) 남은 테스트들은 러너가 빠른 동안만 통과하고, 느려지는 날
        // 「레이트 리밋이 고장났다」처럼 실패한다 — 실제로는 검사 대상이 사라진 것이다.
        Instant before = clock.instant();
        place(Ids.newId(), "frozen-" + Ids.newId());

        assertThat(clock.instant())
                .as("버킷이 받는 시각이 요청 사이에 움직이면 리필이 러너 속도에 달린다")
                .isEqualTo(before);
    }

    @Test
    void 리필은_실제_시간이_흘러도_일어나지_않는다() throws Exception {
        // 위 어설션은 「시계가 멈췄다」까지이고, 이 검사가 그것이 **무엇을 막는지**를 본다.
        // 잠을 자는 것이 여기서는 검사 대상이다 — 막으려는 결함이 「벽시계가 흐르면 토큰이
        // 돌아온다」이므로, 벽시계를 실제로 흘려 보내지 않으면 고쳤다는 근거가 없다.
        // 1초에 1개가 리필이므로 1.2초면 충분하고, 그만큼이 이 클래스가 쓰는 유일한 대기다.
        UUID customer = Ids.newId();
        for (int i = 0; i < CAPACITY; i++) {
            assertThat(place(customer, "drift-" + customer + "-" + i)).isEqualTo(201);
        }

        Thread.sleep(Duration.ofMillis(1_200));

        assertThat(place(customer, "drift-" + customer + "-after"))
                .as("실제 시계로 돌면 여기서 토큰 하나가 돌아와 201 이 된다 — 2026-09-23 CI 의 실패다")
                .isEqualTo(429);
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
