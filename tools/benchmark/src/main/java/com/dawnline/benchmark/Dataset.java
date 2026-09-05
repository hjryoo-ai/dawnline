package com.dawnline.benchmark;

import java.util.Arrays;
import java.util.Locale;

/**
 * 벤치마크 데이터셋 규모 (DESIGN.md §6.9).
 *
 * <p>seed 를 여기 고정하지 않는다 — 같은 데이터셋을 다른 seed 로 여러 번 돌려야 "이 전략이 이
 * 배치에서만 좋은 것" 인지 알 수 있기 때문이다. seed 는 실행 인자다.
 */
public enum Dataset {

    /** CI 회귀 게이트가 쓰는 크기 (§6.9). 빨라야 한다. */
    SMALL(500, 5),
    MEDIUM(2_000, 20),
    /** Phase 3 DoD 의 "5,000건 통합 계획". */
    LARGE(5_000, 40),
    /** Phase 7 피크. 기본 실행에는 넣지 않는다. */
    PEAK(15_000, 60);

    private final int orders;
    private final int vehicles;

    Dataset(int orders, int vehicles) {
        this.orders = orders;
        this.vehicles = vehicles;
    }

    /** 주문 수. */
    public int orders() {
        return orders;
    }

    /** 차량 수. */
    public int vehicles() {
        return vehicles;
    }

    /** 소문자 이름 ({@code --dataset small}). */
    public String cliName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * CLI 인자에서 찾는다.
     *
     * @param value {@code small} 같은 소문자 이름
     */
    public static Dataset fromCli(String value) {
        return Arrays.stream(values())
                .filter(dataset -> dataset.cliName().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "알 수 없는 데이터셋: %s (가능한 값: %s)".formatted(value,
                                Arrays.stream(values()).map(Dataset::cliName).toList())));
    }
}
