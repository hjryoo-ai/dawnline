package com.dawnline.sim.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
 */
@ConfigurationProperties(prefix = "dawnline.sim")
public record SimProperties(
        @DefaultValue("smoke") String scenario,
        @DefaultValue("http://localhost:8081") String baseUrl,
        @DefaultValue("5000") long requestTimeoutMs,
        @DefaultValue Map<String, Scenario> scenarios) {

    public SimProperties {
        scenarios = scenarios == null ? Map.of() : Map.copyOf(scenarios);
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
     * 시나리오 하나.
     *
     * @param orders        보낼 주문 수
     * @param ratePerSecond 초당 주문 수. 이 도구는 부하 측정기가 아니다 — 부하는 k6 가 잰다
     *                      ({@code tools/k6/orders.js}). 여기서 속도를 두는 것은 <em>흐름</em>을
     *                      만들기 위해서다
     * @param seed          난수 seed. 같은 seed 면 같은 주문 200건이 나온다 (불변규칙 12)
     * @param customers     고객 풀 크기. 같은 고객이 여러 건을 내는 것이 현실이고,
     *                      §7.2 레이트 리밋에 닿지 않으려면 {@code orders / customers} 가
     *                      버킷 용량(60)보다 한참 작아야 한다
     * @param coldRatio     냉장 비율 (0.0 ~ 1.0)
     * @param tierWeights   티어별 가중치. 키는 API 에 보내는 문자열 그대로다
     */
    public record Scenario(
            @DefaultValue("200") int orders,
            @DefaultValue("20") int ratePerSecond,
            @DefaultValue("1") long seed,
            @DefaultValue("1000") int customers,
            @DefaultValue("0.25") double coldRatio,
            @DefaultValue Map<String, Integer> tierWeights) {

        public Scenario {
            if (orders < 1) {
                throw new IllegalArgumentException("orders 는 1 이상이어야 합니다");
            }
            if (ratePerSecond < 1) {
                throw new IllegalArgumentException("rate-per-second 는 1 이상이어야 합니다");
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
    }
}
