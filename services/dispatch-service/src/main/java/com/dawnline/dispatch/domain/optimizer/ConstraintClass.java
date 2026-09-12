package com.dawnline.dispatch.domain.optimizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 수요가 요구하는 <strong>제약의 조합</strong> ([ADR-033] 의 축).
 *
 * <h2>희소한 것은 능력이 아니라 조합이다</h2>
 * 능력별로 재면 아무것도 보이지 않는다. {@code peak} 에서 위험물 차량의 남은 슬롯은 287개인데,
 * <strong>냉장 ∧ 위험물</strong>을 실을 수 있는 9대의 남은 슬롯은 <strong>5개</strong>다 —
 * 「위험물 수요 284 ≤ 여유 287, 충분하다」로 읽고 9건을 미배정으로 남기게 된다
 * ({@code docs/benchmarks/phase4-scarce-seats.md} §3).
 *
 * <h2>조합을 손으로 나열하지 않는다</h2>
 * {@link #all()} 은 <strong>두 축에서 뽑는다</strong>. 축이 늘면 조합이 따라 늘고, 늘어난 조합이
 * 조용히 검사 밖에 남지 않는다 — 드는 방식이 아니라 빼는 방식이다(CLAUDE.md · §13).
 *
 * <h2>포함 관계가 이 타입의 쓸모다</h2>
 * {@link #covers(ConstraintClass)} 가 「더 특정한 수요는 덜 특정한 자리에 앉을 수 있다」를
 * 한 곳에 적어 둔다. 냉장 ∧ 위험물 stop 은 냉장 좌석에 앉을 수 있지만 그 반대는 아니다 —
 * 이 비대칭이 좌석 예약([ADR-039])과 고정비 하한([ADR-038])의 축을 동시에 정한다.
 *
 * @param cold   냉장을 요구하는가
 * @param hazmat 위험물인가
 */
public record ConstraintClass(boolean cold, boolean hazmat) {

    /** 아무 능력도 요구하지 않는 수요. 어떤 차량이든 싣는다. */
    public static final ConstraintClass NONE = new ConstraintClass(false, false);

    private static final List<ConstraintClass> ALL = enumerate();

    /**
     * 모든 조합. 축의 곱이므로 {@code 2^축수} 개다.
     *
     * <p>순서는 축의 순서(냉장 → 위험물)로 고정된다 — 같은 문제가 같은 결과를 내야 하기
     * 때문이다(불변규칙 12).
     */
    public static List<ConstraintClass> all() {
        return ALL;
    }

    /**
     * 이 화물이 요구하는 조합.
     *
     * @param parcel 화물. 통합된 stop 이면 속성은 이미 OR 로 합쳐져 있다
     */
    public static ConstraintClass of(Parcel parcel) {
        Objects.requireNonNull(parcel, "parcel");
        return new ConstraintClass(parcel.requiresCold(), parcel.hazmat());
    }

    /**
     * 이 stop 이 요구하는 조합. <strong>통합 후</strong>의 값이다 — 한 건이라도 냉장이면
     * 그 stop 전체가 냉장을 요구한다(§6.5 1단계).
     *
     * @param stop 통합된 stop
     */
    public static ConstraintClass of(Stop stop) {
        Objects.requireNonNull(stop, "stop");
        return of(stop.parcel());
    }

    /** 아무 능력도 요구하지 않는가. */
    public boolean isNone() {
        return !cold && !hazmat;
    }

    /**
     * 요구하는 능력의 수. <strong>클수록 특정한 조합</strong>이고, 그만큼 실을 수 있는 차가 적다.
     */
    public int specificity() {
        return (cold ? 1 : 0) + (hazmat ? 1 : 0);
    }

    /**
     * 이 조합이 {@code other} 를 포함하는가 — 즉 {@code other} 를 위해 마련한 자리에 이 조합의
     * 수요가 앉을 수 있는가.
     *
     * @param other 비교할 조합
     */
    public boolean covers(ConstraintClass other) {
        Objects.requireNonNull(other, "other");
        return (cold || !other.cold) && (hazmat || !other.hazmat);
    }

    /**
     * 이 차량이 이 조합을 실을 수 있는가 (§6.3 {@code VEHICLE_ATTRIBUTE_MATCH} 와 같은 방향).
     *
     * @param vehicle 차량
     */
    public boolean carriedBy(VehicleSpec vehicle) {
        Objects.requireNonNull(vehicle, "vehicle");
        return (!cold || vehicle.attrs().cold()) && (!hazmat || vehicle.attrs().allowsHazmat());
    }

    /** 사람이 읽을 이름. 설명(§6.3)과 측정 문서가 같은 말을 쓰도록 여기 둔다. */
    public String label() {
        if (cold && hazmat) {
            return "냉장∧위험물";
        }
        if (cold) {
            return "냉장";
        }
        return hazmat ? "위험물" : "일반";
    }

    private static List<ConstraintClass> enumerate() {
        List<ConstraintClass> out = new ArrayList<>();
        for (boolean cold : new boolean[] {false, true}) {
            for (boolean hazmat : new boolean[] {false, true}) {
                out.add(new ConstraintClass(cold, hazmat));
            }
        }
        return List.copyOf(out);
    }
}
