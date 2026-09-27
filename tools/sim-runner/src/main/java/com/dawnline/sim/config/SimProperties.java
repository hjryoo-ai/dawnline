package com.dawnline.sim.config;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code dawnline.sim.*} — 시나리오 정의 (DESIGN.md §5.6).
 *
 * <p>시나리오는 {@code scenarios.yml} 에 이름별로 적고, {@code --dawnline.sim.scenario=smoke} 로
 * 고른다. 값을 코드에 두지 않는 이유는 §5.6 이 "시나리오 YAML" 이라고 정했기 때문이고,
 * 실제로도 피크 곡선·비율은 실행할 때마다 바뀌는 값이다.
 *
 * @param scenario         실행할 시나리오 이름
 * @param baseUrl          order-service 주소
 * @param requestTimeoutMs 요청 하나의 타임아웃(ms)
 * @param scenarios        이름 → 시나리오
 * @param ops              ops-api — 함대 단계가 운영자의 토큰으로 부른다(ADR-067)
 */
@ConfigurationProperties(prefix = "dawnline.sim")
public record SimProperties(
        @DefaultValue("smoke") String scenario,
        @DefaultValue("http://localhost:8081") String baseUrl,
        @DefaultValue("5000") long requestTimeoutMs,
        @DefaultValue Map<String, Scenario> scenarios,
        @DefaultValue Ops ops) {

    public SimProperties {
        scenarios = scenarios == null ? Map.of() : Map.copyOf(scenarios);
        ops = ops == null ? new Ops("http://localhost:8085", "", 60_000, 900, 120, 5) : ops;
        if (requestTimeoutMs < 1) {
            throw new IllegalArgumentException("dawnline.sim.request-timeout-ms 는 1 이상이어야 합니다");
        }
    }

    /**
     * 고른 시나리오. 없으면 <strong>있는 이름을 함께</strong> 알려 준다 — 오타 하나에
     * "시나리오를 찾을 수 없다" 만 나오면 다음에 뭘 쳐야 할지 알 수 없다.
     */
    public Scenario selected() {
        Scenario found = scenarios.get(scenario);
        if (found == null) {
            throw new IllegalArgumentException(
                    "시나리오 '%s' 가 없습니다. 있는 것: %s".formatted(scenario, scenarios.keySet()));
        }
        return found;
    }

    /**
     * 고른 시나리오. 없으면 {@code null} 이다.
     *
     * <p>{@link #selected()} 와 달리 <strong>없는 이름에도 던지지 않는다</strong>. 배선이 이
     * 값을 읽는데, 오타 하나가 컨텍스트 기동 실패로 나타나면 "있는 것: [...]" 안내가 스택
     * 트레이스 아래로 묻힌다. 이름이 틀렸다는 것은 실행 시점에 {@link #selected()} 가 말한다.
     */
    public @Nullable Scenario selectedOrNull() {
        return scenarios.get(scenario);
    }

    /**
     * 시나리오 하나.
     *
     * @param orders        보낼 주문 수
     * @param ratePerSecond 초당 주문 수. 이 도구는 부하 측정기가 아니다 — 부하는 k6 가 잰다
     *                      ({@code tools/k6/orders.js}). 여기서 속도를 두는 것은 <em>흐름</em>을
     *                      만들기 위해서다. 소수를 받는다 — 창 시나리오는 「한 시간에 9,000건」(2.5)이다
     * @param seed          난수 seed. 같은 seed 면 같은 주문 200건이 나온다 (불변규칙 12)
     * @param customers     고객 풀 크기. 같은 고객이 여러 건을 내는 것이 현실이고,
     *                      §7.2 레이트 리밋에 닿지 않으려면 {@code orders / customers} 가
     *                      버킷 용량(60)보다 한참 작아야 한다
     * @param coldRatio     냉장 비율 (0.0 ~ 1.0)
     * @param tierWeights   티어별 가중치. 키는 API 에 보내는 문자열 그대로다
     * @param driver        기사 시뮬레이터 설정 (Phase 5-2). 없으면 주문만 넣고 끝난다 —
     *                      그래야 {@code smoke} 가 브로커 없이 돈다
     * @param startAt       창의 시작 — 유효 시각(주입 시계)의 KST {@code HH:mm}. 있으면 그 시각까지 기다린 뒤 보낸다
     *                      (부록 A 「창은 하나다」, ADR-066). 없으면 곧바로 보낸다
     * @param fleet         함대 단계 — {@code feasible}(80% 기준이 낸 만큼 증차) · {@code as-is}(재고 리포트하되 더하지 않는다).
     *                      없으면 함대 단계가 없다. 창({@code start-at})이 있어야 한다 — 증차는 창의 DAWN 웨이브에 한다(ADR-067)
     */
    public record Scenario(
            @DefaultValue("200") int orders,
            @DefaultValue("20") double ratePerSecond,
            @DefaultValue("1") long seed,
            @DefaultValue("1000") int customers,
            @DefaultValue("0.25") double coldRatio,
            @DefaultValue Map<String, Integer> tierWeights,
            @Nullable Driver driver,
            @Nullable String startAt,
            @Nullable Fleet fleet) {

        public Scenario {
            if (orders < 1) {
                throw new IllegalArgumentException("orders 는 1 이상이어야 합니다");
            }
            if (!(ratePerSecond > 0.0)) {
                throw new IllegalArgumentException("rate-per-second 는 0 보다 커야 합니다");
            }
            if (startAt != null) {
                try {
                    LocalTime.parse(startAt);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("start-at 은 HH:mm 이어야 합니다: " + startAt, e);
                }
            }
            if (fleet != null && startAt == null) {
                throw new IllegalArgumentException("fleet 은 창(start-at)이 있는 시나리오에만 둔다 — 증차는 창의 DAWN 웨이브에 한다");
            }
            if (driver != null && driver.routes() == null && fleet == null) {
                throw new IllegalArgumentException(
                        "driver.routes 가 없으면 계획이 수를 낸다 — 그 수를 읽는 함대 단계(fleet)가 있어야 한다");
            }
            if (customers < 1) {
                throw new IllegalArgumentException("customers 는 1 이상이어야 합니다");
            }
            if (coldRatio < 0.0 || coldRatio > 1.0) {
                throw new IllegalArgumentException("cold-ratio 는 0.0 ~ 1.0 이어야 합니다");
            }
            tierWeights = tierWeights == null ? Map.of() : new LinkedHashMap<>(tierWeights);
            if (tierWeights.isEmpty()) {
                throw new IllegalArgumentException("tier-weights 가 비어 있습니다 (scenarios.yml)");
            }
            if (tierWeights.values().stream().anyMatch(weight -> weight == null || weight < 0)) {
                throw new IllegalArgumentException("tier-weights 의 가중치는 0 이상이어야 합니다");
            }
            if (tierWeights.values().stream().mapToInt(Integer::intValue).sum() == 0) {
                throw new IllegalArgumentException("tier-weights 의 합이 0 입니다");
            }
            tierWeights = Map.copyOf(tierWeights);
        }

        /** 함대 단계 (ADR-067). */
        public enum Fleet {
            /** 80% 기준(FleetFeasibility)이 낸 부족 대수만큼 증차한다 — {@code peak-day}. */
            FEASIBLE,
            /** 재고 리포트하되 더하지 않는다 — 「증차 없음」, {@code overload-day} 와 평일 셋. */
            AS_IS
        }

        /** @return 창의 시작(KST), 없으면 {@code null} */
        public @Nullable LocalTime windowStart() {
            return startAt == null ? null : LocalTime.parse(startAt);
        }

        /**
         * 기사 시뮬레이터 설정 (IMPLEMENTATION_PLAN Phase 5-2).
         *
         * <p>난수 seed 는 여기 없다 — 시나리오의 {@code seed} 를 그대로 쓴다. 주문 생성과 지연
         * 주입이 같은 seed 에서 나와야 "이 시나리오" 하나가 재현된다 (불변규칙 12).
         *
         * @param routes                 기다릴 라우트 수. 이만큼 끝나야 성공이다 — 적게 온 것도
         *                               실패다({@code DriverReport.isSuccess}). <strong>없으면 계획이 낸다</strong> —
         *                               창의 웨이브들의 {@code route_count} 합(창 시나리오, ADR-067 결정 6)
         * @param speed                  배속. 시뮬레이션 초 ÷ 벽시계 초. 0 이하면 대기 없이 돈다.
         *                               <strong>at-risk 쿨다운 TTL 은 벽시계 5분</strong>이므로
         *                               배속이 크면 라우트당 at-risk 가 한 번만 보인다 —
         *                               "몇 번 났다" 를 보려면 1 로 둔다 (ADR-046, package-info)
         * @param timeoutSeconds         라우트를 기다리는 상한(초)
         * @param scanRetrySeconds       스캔 404 · 409 재시도와 전송 실패(타임아웃) 재전송의 상한(초). 넘기면 그 라우트를 포기한다 —
         *                               조용히 무한 재시도하면 시나리오 결과가 오염된다
         * @param scanBaseUrl            tracking-service 주소
         * @param delayProbability       구간에 지연이 걸릴 확률 (0.0 ~ 1.0)
         * @param delayMagnitude         지연의 최대 비율. 0.5 면 계획 이동 시간의 최대 1.5배
         * @param failureProbability     전달이 실패할 확률 (0.0 ~ 1.0)
         * @param departureDelaySeconds  캠프 출발 지연의 최대 초. §5.4 가 말하는 가장 흔한
         *                               지연 원인이고, 첫 도착 스캔 전에 이미 알 수 있는 위험이다
         */
        public record Driver(
                @Nullable Integer routes,
                @DefaultValue("600") double speed,
                @DefaultValue("300") long timeoutSeconds,
                @DefaultValue("30") long scanRetrySeconds,
                @DefaultValue("http://localhost:8084") String scanBaseUrl,
                @DefaultValue("0.0") double delayProbability,
                @DefaultValue("0.0") double delayMagnitude,
                @DefaultValue("0.0") double failureProbability,
                @DefaultValue("0") long departureDelaySeconds) {

            public Driver {
                if (routes != null && routes < 1) {
                    throw new IllegalArgumentException("driver.routes 는 1 이상이어야 합니다");
                }
                if (timeoutSeconds < 1) {
                    throw new IllegalArgumentException("driver.timeout-seconds 는 1 이상이어야 합니다");
                }
                if (scanRetrySeconds < 0) {
                    throw new IllegalArgumentException("driver.scan-retry-seconds 는 0 이상이어야 합니다");
                }
                requireRatio(delayProbability, "driver.delay-probability");
                requireRatio(failureProbability, "driver.failure-probability");
                if (delayMagnitude < 0.0) {
                    throw new IllegalArgumentException("driver.delay-magnitude 는 0 이상이어야 합니다");
                }
                if (departureDelaySeconds < 0) {
                    throw new IllegalArgumentException("driver.departure-delay-seconds 는 0 이상이어야 합니다");
                }
                scanBaseUrl = Objects.requireNonNull(scanBaseUrl, "driver.scan-base-url");
            }

            private static void requireRatio(double value, String name) {
                if (!(value >= 0.0 && value <= 1.0)) {
                    throw new IllegalArgumentException("%s 는 0.0 ~ 1.0 이어야 합니다: %s".formatted(name, value));
                }
            }
        }
    }

    /**
     * ops-api — 함대 단계가 부르는 곳 (ADR-067). sim-runner 는 dispatch 에 직접 닿지 않는다: 증차는 운영자의 커맨드이고 감사 행이
     * 남아야 한다(ADR-055 가 닫은 경로를 다시 열지 않는다).
     *
     * @param baseUrl               ops-api 주소
     * @param token                 운영자 JWT({@code OPS_OPERATOR} — {@code make token ROLE=OPS_OPERATOR}). 비었으면 함대 단계가
     *                              시작하지 않고 실패한다 — 전제를 볼 수 없다
     * @param requestTimeoutMs      요청 하나의 타임아웃(ms). 함대 판정은 웨이브 후보 전부를 통합한다 — ops-api 의 dispatch 읽기
     *                              타임아웃(60초)과 같게 둔다
     * @param planTimeoutSeconds    창의 웨이브가 전부 계획되기를 기다리는 상한(초)
     * @param releaseTimeoutSeconds 비활성화의 409({@code vehicle-in-service})를 다시 시도하는 상한(초) — 기사가 끝낸 stop 을
     *                              dispatch 가 소비하기까지의 지연
     * @param pollSeconds           웨이브 상태 · 비활성화 재시도의 간격(초)
     */
    public record Ops(
            @DefaultValue("http://localhost:8085") String baseUrl,
            @DefaultValue("") String token,
            @DefaultValue("60000") long requestTimeoutMs,
            @DefaultValue("900") long planTimeoutSeconds,
            @DefaultValue("120") long releaseTimeoutSeconds,
            @DefaultValue("5") long pollSeconds) {

        public Ops {
            baseUrl = Objects.requireNonNull(baseUrl, "ops.base-url");
            token = token == null ? "" : token.strip();
            if (requestTimeoutMs < 1 || planTimeoutSeconds < 1 || releaseTimeoutSeconds < 0 || pollSeconds < 1) {
                throw new IllegalArgumentException("dawnline.sim.ops 의 시간 값이 올바르지 않다");
            }
        }
    }
}
