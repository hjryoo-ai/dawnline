package com.dawnline.benchmark;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

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
     * {@code DatasetFeasibilityTest} 의 기준을 모두 만족하는 <strong>최소 대수</strong>가 76 이다.
     * {@link #OVERLOAD} 와 <strong>같은 주문·같은 seed</strong> 라 차이가 오직 대수뿐이다.
     *
     * <h2>무는 축이 옮겨 갔다 (2026-09-28, ADR-075 결정 1)</h2>
     * 창이 셋이던 때는 같은 건물의 주문이 창마다 갈려 통합 후 stop 이 8,411 이었고, 무는 축은 <strong>stop</strong>
     * 이었다({@code 0.8 × 120 × 88 ≥ 8,411} 의 최소 88). 창이 하나가 되자 통합이 늘어 stop 이 5,811 이 됐고, 무는 축은
     * <strong>총 중량 70%</strong> 다 — 대수를 바꿔 가며 기준을 돌렸을 때 75대에서 실패하고 76대에서 통과했다.
     */
    PEAK(15_000, 76),

    /**
     * <strong>일부러 용량을 넘긴</strong> 웨이브 — 알고리즘이 아니라 <em>과부하 거동</em>을 재는
     * 자리다. {@link #PEAK} 와 주문·seed 가 같고 대수만 다르다.
     *
     * <h2>대수는 선언한 비율에서 나온다 (2026-09-28, ADR-075 결정 1)</h2>
     * <strong>선언: 무는 축(stop)의 수요가 80% 슬롯의 1.5배다.</strong> 정수 대수로는 그 비율을 넘지 않는 최소 대수이고 —
     * {@link #PEAK} 의 「기준을 만족하는 최소 대수」와 같은 형태 — {@code ⌈5,811 ÷ (1.5 × 0.8 × 120)⌉ = 41} 이다(147.6%).
     * 중량도 원래 용량을 넘는다 — 「다 못 싣는다」가 <strong>두 축에서</strong> 참이다({@code DatasetFeasibilityTest}).
     *
     * <p>처음 판은 60대였고 「stop 8,411 이 5,760 슬롯을 46% 초과」라고 적었다. 그 146% 는 옛 통합 위에서 60대가 낸
     * <em>결과</em>였지 선언이 아니었다 — 창이 하나가 되어 stop 이 5,811 로 줄자 60대는 80% 슬롯의 101%, 중량은 87.5% 로
     * 겨우 넘거나 넘지 못했다. 유도의 방향을 바꿨다: 비율을 선언하고 대수를 낸다.
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
    OVERLOAD(15_000, 41),

    /**
     * <strong>일부러 창을 셋 둔</strong> 웨이브 — 스트레스 레짐이지 기본 레짐이 아니다
     * (<a href="../../../../../../../docs/adr/ADR-075-promise-start-is-a-floor-vehicle-time-belongs-to-the-earlier-plan.md">ADR-075</a> 결정 1).
     * {@link #MEDIUM} 과 주문 · 좌표 · 화물 · 차량 · seed 가 같고 <strong>약속창만</strong> 다르다.
     *
     * <h2>무엇을 모델링하는가</h2>
     * §2.2 의 웨이브는 (캠프, 티어, 컷오프)이고 창은 컷오프에서 유도되므로 <strong>웨이브 하나에 창은 하나</strong>다. 이 데이터셋의
     * 창 셋(계획 시작 +2h · +4h · +6h, 각 4시간)은 어느 실제 웨이브와도 맞지 않는다 — Phase 3 부터 2026-09-28 까지 모든 데이터셋의
     * 모양이었고, 기다림(§2.2 약속창 시작은 하한)을 넣자 비용이 두 배가 됐다. 재는 것은 둘이다.
     *
     * <ol>
     *   <li><strong>시간 룰이 갈리는가</strong> — 창이 다른 stop 이 한 라우트에 섞일 때 근무창 · 지각이 실제로 무는가</li>
     *   <li><strong>시각을 보지 않는 줄 세우기의 약점</strong> — 거리로 줄 세우면 늦은 창의 stop 에 일찍 가서 기다린다(§6.5 의 반대
     *       측정, 원장 A39)</li>
     * </ol>
     *
     * <p>회귀 게이트 밖이고, §6.9 비교표에서 {@link #OVERLOAD} 처럼 따로 적는다.
     */
    MIXED_WINDOWS(2_000, 20, Windows.STAGGERED_THREE);

    /** 웨이브 하나의 약속창 모양. */
    public enum Windows {
        /** 웨이브의 (티어, 컷오프)에서 유도한 창 하나 — §2.2. */
        ONE_PER_WAVE,
        /** 두 시간씩 밀린 4시간 창 셋 — 어느 실제 웨이브에도 없다({@link #MIXED_WINDOWS}). */
        STAGGERED_THREE
    }

    private final int orders;
    private final int vehicles;
    private final Windows windows;

    Dataset(int orders, int vehicles) {
        this(orders, vehicles, Windows.ONE_PER_WAVE);
    }

    Dataset(int orders, int vehicles, Windows windows) {
        this.orders = orders;
        this.vehicles = vehicles;
        this.windows = windows;
    }

    /** 주문 수. */
    public int orders() {
        return orders;
    }

    /** 차량 수. */
    public int vehicles() {
        return vehicles;
    }

    /** 웨이브 하나의 약속창 모양. */
    public Windows windows() {
        return windows;
    }

    /** 소문자 · 하이픈 이름 ({@code --dataset small}, {@code --dataset mixed-windows}). */
    public String cliName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** 사용법에 적는 값 목록 — 열거하지 않고 enum 에서 뽑는다({@code small|medium|…}). */
    public static String cliNames() {
        return Arrays.stream(values()).map(Dataset::cliName).collect(Collectors.joining("|"));
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
