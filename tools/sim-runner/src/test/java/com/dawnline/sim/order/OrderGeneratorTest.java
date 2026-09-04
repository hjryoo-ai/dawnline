package com.dawnline.sim.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.sim.config.SimProperties.Scenario;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 주문 생성기 — 결정론(불변규칙 12)과 서버 계약 준수. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OrderGeneratorTest {

    private static final Scenario SCENARIO = new Scenario(
            200, 20, 20260904L, 200, 0.25, Map.of("DAWN", 5, "SAME_DAY", 3, "NEXT_DAY", 2));

    private static RandomGenerator random(long seed) {
        return RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
    }

    private static List<GeneratedOrder> generate(Scenario scenario, long seed, int count) {
        OrderGenerator generator = new OrderGenerator(scenario, random(seed));
        List<GeneratedOrder> orders = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            orders.add(generator.next(i));
        }
        return orders;
    }

    @Test
    void 같은_seed_는_같은_주문_200건을_낸다() {
        assertThat(generate(SCENARIO, 20260904L, 200))
                .isEqualTo(generate(SCENARIO, 20260904L, 200));
    }

    @Test
    void 다른_seed_는_다른_주문을_낸다() {
        assertThat(generate(SCENARIO, 1L, 50))
                .isNotEqualTo(generate(SCENARIO, 2L, 50));
    }

    @Test
    void 우편번호는_좌표를_찾을_수_있는_접두어만_쓴다() {
        // 표가 PostalPrefixGeocoder 와 어긋나면 실제 실행이 400 으로 무너진다. 그 전에 잡는다.
        assertThat(generate(SCENARIO, 7L, 500))
                .allSatisfy(order -> {
                    assertThat(order.postalCode()).matches("^[0-9]{5}$");
                    assertThat(OrderGenerator.POSTAL_PREFIXES).contains(order.postalCode().substring(0, 2));
                });
    }

    @Test
    void 본문은_PlaceOrderRequest_의_제약을_만족한다() {
        assertThat(generate(SCENARIO, 11L, 500)).allSatisfy(order -> {
            assertThat(order.addressLine()).isNotBlank().hasSizeLessThanOrEqualTo(200);
            assertThat(order.serviceTier()).isIn("DAWN", "SAME_DAY", "NEXT_DAY");
            assertThat(order.parcel().weightG()).isBetween(0, 1_000_000);
            assertThat(order.parcel().volumeCm3()).isBetween(0, 1_000_000);
            assertThat(order.items()).isNotEmpty().hasSizeLessThanOrEqualTo(200);
            assertThat(order.items()).allSatisfy(item -> {
                assertThat(item.sku()).matches("^[A-Za-z0-9._-]{1,32}$");
                assertThat(item.qty()).isGreaterThanOrEqualTo(1);
            });
        });
    }

    @Test
    void 고객_id_는_서버가_파싱할_수_있는_UUID_다() {
        assertThat(generate(SCENARIO, 3L, 100)).allSatisfy(order -> {
            assertThat(order.customerId().version()).isEqualTo(4);
            assertThat(order.customerId().variant()).isEqualTo(2);
        });
    }

    @Test
    void 고객_풀은_설정한_크기를_넘지_않는다() {
        Scenario small = new Scenario(200, 20, 5L, 10, 0.25, Map.of("DAWN", 1));
        Set<Object> customers = new HashSet<>();
        generate(small, 5L, 200).forEach(order -> customers.add(order.customerId()));
        assertThat(customers).hasSizeLessThanOrEqualTo(10);
    }

    @Test
    void 티어_가중치가_비율에_반영된다() {
        List<GeneratedOrder> orders = generate(SCENARIO, 42L, 3000);
        long dawn = orders.stream().filter(o -> "DAWN".equals(o.serviceTier())).count();
        long nextDay = orders.stream().filter(o -> "NEXT_DAY".equals(o.serviceTier())).count();
        // 5 : 2 — 표본 3,000건이면 대소는 확실하고, 비율도 넉넉한 범위 안에 든다.
        assertThat(dawn).isGreaterThan(nextDay);
        assertThat(dawn / (double) orders.size()).isBetween(0.44, 0.56);
    }

    @Test
    void 냉장_비율이_설정을_따른다() {
        List<GeneratedOrder> orders = generate(SCENARIO, 13L, 3000);
        long cold = orders.stream().filter(o -> o.parcel().requiresCold()).count();
        assertThat(cold / (double) orders.size()).isBetween(0.21, 0.29);
    }

    @Test
    void 티어_풀은_설정_맵의_순서에_좌우되지_않는다() {
        // 같은 가중치를 다른 순서로 넣어도 같은 수열이 나와야 한다. HashMap 의 순회 순서에
        // 결정론이 기대면, 키가 하나 늘어나는 날 재현이 조용히 깨진다.
        Scenario a = new Scenario(10, 5, 9L, 10, 0.0,
                Map.of("DAWN", 2, "SAME_DAY", 1, "NEXT_DAY", 1));
        Scenario b = new Scenario(10, 5, 9L, 10, 0.0,
                Map.of("NEXT_DAY", 1, "DAWN", 2, "SAME_DAY", 1));
        assertThat(generate(a, 9L, 50)).isEqualTo(generate(b, 9L, 50));
    }
}
