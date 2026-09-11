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

    /**
     * 실현 가능한 최대 규모. <strong>차량 수는 고르는 값이 아니라 기준이 정한 값이다</strong> —
     * 통합 후 stop 8,411 개에 대해 {@code 0.8 × max-stops(120) × 차량 수 ≥ 8,411} 을 만족하는
     * 최소 대수가 88 이다({@code DatasetFeasibilityTest}). 60 으로 두었던 것이 {@link #OVERLOAD}
     * 이고, 둘은 <strong>같은 주문·같은 seed</strong> 라 차이가 오직 대수뿐이다.
     */
    PEAK(15_000, 88),

    /**
     * <strong>일부러 용량을 넘긴</strong> 웨이브 — 알고리즘이 아니라 <em>과부하 거동</em>을 재는
     * 자리다. {@link #PEAK} 와 주문·seed 가 같고 차량만 60대라, 통합 후 stop 8,411 개가
     * {@code 0.8 × 120 × 60 = 5,760} 슬롯을 <strong>46% 초과</strong>한다.
     *
     * <h2>무엇을 모델링하는가</h2>
     * 웨이브는 (캠프, 티어, 컷오프) 단위다(§2.2·§5.2). 그러므로 <strong>15,000건 한 웨이브는
     * 캠프 하루치를 통째로 한 웨이브에 넣은 것</strong>이지 정상적인 피크 웨이브가 아니다.
     * 이 데이터셋을 「성수기의 정상 부하」로 읽으면 안 된다 — 재려는 것은 셋이다.
     *
     * <ol>
     *   <li><strong>미배정 정책</strong>(ADR-028) — 다 못 실을 때 <em>누가</em> 빠지는가</li>
     *   <li><strong>계획 시간의 상한</strong> — 마감이 없는 단계가 있으면 여기서 드러난다</li>
     *   <li><strong>열화</strong>(§6.7) — FAST 가 실제로 무엇을 줄이는가</li>
     * </ol>
     *
     * <p>실제로 그 셋을 다 드러냈다: 재삽입 O(n²)과 배정·재삽입에 마감이 없다는 사실
     * ({@code docs/benchmarks/phase4-peak-gate.md}). 그래서 이 데이터셋은 «결함» 이 아니라
     * <strong>도구</strong>다 — 다만 §6.9 비교표에서 실현 가능한 데이터셋과 <em>같은 절에
     * 섞지 않는다.</em>
     */
    OVERLOAD(15_000, 60);

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
