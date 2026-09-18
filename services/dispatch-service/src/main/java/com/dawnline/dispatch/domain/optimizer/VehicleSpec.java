package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.TimeWindow;
import java.util.Objects;

/**
 * 계획에 쓰이는 차량 한 대 (DESIGN.md §6.2).
 *
 * <p>근무창이 {@code ShiftWindow} 가 아니라 {@link TimeWindow} 인 이유는 §6.2 에 적었다 — 같은
 * 뜻의 타입을 하나 더 두면 둘 중 하나에만 경계 규칙이 붙는다.
 *
 * <p>{@code vehicles} 테이블의 {@code shift_start}/{@code shift_end} 는 {@code TIME}(벽시계)이고
 * 여기의 {@code shift} 는 <strong>계획 대상 날짜에 붙인 {@link java.time.Instant}</strong> 다.
 * 붙이는 일은 어댑터가 한다 — 순수 함수는 "몇 시" 가 아니라 "언제" 만 다룬다(불변규칙 12).
 *
 * @param id       차량 id
 * @param capacity 적재 용량
 * @param attrs    속성(차종·냉장·위험물)
 * @param shift    근무창. 이 창 안에서 출발하고 복귀해야 한다 (§6.3 {@code SHIFT_WINDOW})
 * @param cost     비용 파라미터
 */
public record VehicleSpec(VehicleId id, Capacity capacity, VehicleAttrs attrs, TimeWindow shift,
        VehicleCost cost) {

    public VehicleSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(capacity, "capacity");
        Objects.requireNonNull(attrs, "attrs");
        Objects.requireNonNull(shift, "shift");
        Objects.requireNonNull(cost, "cost");
    }

    /**
     * <strong>능력이 적은 차량 먼저</strong> (§6.5 3단계 동률 규칙, ADR-031).
     *
     * <p>빈 패킹의 고전 규칙이다 — <em>특수 자원은 그것을 요구하는 수요에 남겨 둔다.</em>
     * 한계비용이 같은 실행 가능 차량이 여럿이면 비냉장을 냉장보다, 위험물 불가를 허용보다,
     * 작은 차를 큰 차보다 먼저 고른다. 냉장 차량을 상온 주문에 써 버리면 뒤에 오는 냉장
     * 주문이 갈 곳을 잃고, 그 손해는 <strong>탐욕이 보지 못하는 미래</strong>에 있다.
     *
     * <p>이 순서가 없으면 동률은 <em>차량 id 순서</em>로 깨진다 — 결정이 아니라 우연이고,
     * 그 우연이 시각과 시드 배분에 따라 통과·실패를 갈랐다(2026-09-08, ADR-031 맥락).
     *
     * <p>마지막 키가 {@code id} 인 것은 <strong>재현성</strong> 때문이다(불변규칙 12).
     * 능력과 용량까지 같은 두 대는 정말로 구별할 것이 없으므로, 그때는 안정적인 값으로
     * 정해야 같은 seed 가 같은 결과를 낸다.
     */
    public static final java.util.Comparator<VehicleSpec> LEAST_CAPABLE_FIRST =
            java.util.Comparator.comparingInt(VehicleSpec::capabilityRank)
                    .thenComparingInt(spec -> spec.capacity().maxWeightG())
                    .thenComparingInt(spec -> spec.capacity().maxVolumeCm3())
                    .thenComparing(spec -> spec.id().value());

    /**
     * 이 차량이 가진 <strong>특수 능력의 수</strong>. 적을수록 흔한 차다.
     *
     * <h2>냉장만 센다 — 위험물은 재 보고 뺐다</h2>
     * 위험물 허용도 하드 룰이 요구할 수 있는 능력이라 같은 논리로 넣었다가 <strong>측정이
     * 반대였다.</strong> `large` 에서 위험물 차량까지 아껴 두자 위험물 미배정이 99 → 115 로
     * <em>늘었고</em> 총비용이 3.9% 올랐다(평균 지각은 18.6 → 13.6분으로 줄었다).
     *
     * <p>이유는 이 규칙의 전제에 있다. 아껴 두기는 <strong>아낀 자원이 나중에 그 수요와
     * 짝지어질 때</strong>만 이득이다. 위험물 주문은 2% 라 흩어져 있고, 뒤로 미룬 위험물
     * 차량이 마지막에 받는 것은 이미 쪼개지고 흩어진 클러스터다 — 탐욕에는 "이 차를 저
     * 수요에 맞춰 두자" 를 볼 눈이 없으므로, 예약은 그냥 더 나쁜 순서가 된다.
     * 냉장은 25% 라 그 짝짓기가 통계적으로 일어난다.
     *
     * <p>측정: `docs/benchmarks/phase3-baseline.md` §4-7. **비율이 바뀌면 다시 잰다.**
     *
     * <p>크기는 별개 축이라 여기 넣지 않고 {@link #LEAST_CAPABLE_FIRST} 의 다음 키로 둔다 —
     * 큰 차는 "능력" 이라기보다 용량이고, 냉장 소형차와 상온 대형차 중 상온 쪽을 남겨야
     * 하는 것이 이 규칙의 요점이기 때문이다. (용량 키는 세 데이터셋에서 수치를 바꾸지
     * 않았다 — 같은 §4-7.)
     */
    public int capabilityRank() {
        return attrs.cold() ? 1 : 0;
    }
}
