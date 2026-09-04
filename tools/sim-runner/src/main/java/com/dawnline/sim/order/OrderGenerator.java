package com.dawnline.sim.order;

import com.dawnline.sim.config.SimProperties.Scenario;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 시나리오 설정으로 주문을 만든다.
 *
 * <h2>같은 seed 면 같은 200건</h2>
 * 불변규칙 12 다. 시뮬레이션 결과를 비교하려면 입력이 같아야 하고, 입력이 같으려면
 * 난수원이 주입되어야 한다. {@link #next(int)} 는 호출 순서대로 난수를 소비하므로
 * <em>같은 순서로 부르면</em> 같은 결과가 나온다.
 *
 * <p>티어 가중치 맵은 순서가 없는 {@code Map} 이라 그대로 순회하면 JVM 마다 결과가 달라질 수
 * 있다. 그래서 키를 정렬해 풀을 만든다 — 결정론이 설정 자료구조의 우연에 기대면 안 된다.
 */
public final class OrderGenerator {

    /**
     * 좌표를 찾을 수 있는 우편번호 앞 2자리.
     *
     * <p>order-service 의 {@code PostalPrefixGeocoder.ANCHORS} 와 같아야 한다. 그 표는 아직
     * 어떤 계약 파일에도 없으므로(권역 데이터의 주인은 Phase 2 의 fulfillment-service 다)
     * 여기서는 <strong>어긋나면 드러나게</strong> 하는 쪽을 택했다 — 어긋난 접두어는 400 이
     * 되고, {@link ScenarioReport} 가 그 코드를 세어서 실행이 실패로 끝난다.
     */
    static final List<String> POSTAL_PREFIXES = List.of(
            "01", "02", "03", "04", "05", "06", "07", "08",
            "10", "11", "12", "13", "14", "15", "16", "17", "18",
            "21", "22");

    private static final int MIN_WEIGHT_G = 300;
    private static final int MAX_WEIGHT_G = 12_000;
    private static final int MIN_VOLUME_CM3 = 1_000;
    private static final int MAX_VOLUME_CM3 = 40_000;
    private static final int MAX_ITEMS = 3;
    private static final int SKU_SPACE = 2_000;

    private final Scenario scenario;
    private final RandomGenerator random;
    private final List<UUID> customerPool;
    private final List<String> tierPool;

    /**
     * @param scenario 시나리오
     * @param random   난수원. seed 를 고정해 넘긴다 (불변규칙 12)
     */
    public OrderGenerator(Scenario scenario, RandomGenerator random) {
        this.scenario = Objects.requireNonNull(scenario, "scenario");
        this.random = Objects.requireNonNull(random, "random");
        this.customerPool = customerPool(scenario.customers(), random);
        this.tierPool = tierPool(scenario.tierWeights());
    }

    /**
     * 다음 주문.
     *
     * @param sequence 0부터의 일련번호. 주소·SKU 를 흩뿌리는 데 쓴다
     */
    public GeneratedOrder next(int sequence) {
        UUID customerId = customerPool.get(random.nextInt(customerPool.size()));
        String tier = tierPool.get(random.nextInt(tierPool.size()));
        String postalCode = POSTAL_PREFIXES.get(random.nextInt(POSTAL_PREFIXES.size()))
                + "%03d".formatted(random.nextInt(1000));
        String addressLine = "테스트로 %d길 %d".formatted(sequence % 500 + 1, random.nextInt(90) + 1);

        GeneratedOrder.Parcel parcel = new GeneratedOrder.Parcel(
                MIN_WEIGHT_G + random.nextInt(MAX_WEIGHT_G - MIN_WEIGHT_G),
                MIN_VOLUME_CM3 + random.nextInt(MAX_VOLUME_CM3 - MIN_VOLUME_CM3),
                random.nextDouble() < scenario.coldRatio(),
                false);

        int itemCount = 1 + random.nextInt(MAX_ITEMS);
        List<GeneratedOrder.Item> items = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            items.add(new GeneratedOrder.Item(
                    "SKU-%05d".formatted(random.nextInt(SKU_SPACE)),
                    1 + random.nextInt(3)));
        }
        return new GeneratedOrder(customerId, tier, addressLine, postalCode, parcel, items);
    }

    /** 고객 풀. UUID 버전·변형 비트를 제대로 세워 서버의 {@code UUID.fromString} 이 받게 한다. */
    private static List<UUID> customerPool(int size, RandomGenerator random) {
        List<UUID> pool = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long most = (random.nextLong() & ~0xF000L) | 0x4000L;          // version 4
            long least = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // variant
            pool.add(new UUID(most, least));
        }
        return List.copyOf(pool);
    }

    /** 가중치를 펼친 티어 풀. 키를 정렬해 순서가 설정 맵의 우연에 좌우되지 않게 한다. */
    private static List<String> tierPool(Map<String, Integer> weights) {
        List<String> pool = new ArrayList<>();
        weights.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> {
                    for (int i = 0; i < entry.getValue(); i++) {
                        pool.add(entry.getKey());
                    }
                });
        return List.copyOf(pool);
    }
}
