package com.dawnline.sim.order;

import com.dawnline.sim.config.SimProperties.Scenario.Cancel;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * 어느 주문을 언제 취소하는가 — seed 가 정한다 (7-4 turbulent, 불변규칙 12).
 *
 * <p>주문마다 <strong>두 번</strong> 뽑는다 — 대상인가, 언제인가. 대상이 아닌 주문(DAWN 이 아닌 티어 포함)도 똑같이 두 번 뽑는다: 뽑는 수가
 * 주문의 내용에 따라 달라지면 한 주문의 티어가 뒤의 모든 주문의 결정을 바꾸고, 같은 seed 가 같은 취소 집합을 내지 않는다.
 */
public final class CancelPlan {

    /** 언제 취소하는가. */
    public enum When {
        /** 취소하지 않는다. */
        NONE,
        /** 접수 직후, 창 안에서 — 웨이브가 닫히기 전이다. */
        BEFORE_PLAN,
        /** 계획이 발행된 뒤 — 대부분 order-service 의 409 이고, 그 창에 든 것만 dispatch 에 닿는다. */
        AFTER_PUBLISH
    }

    /** 대상 티어 — 창 시나리오가 재는 범위다(리포트 §1). */
    static final String TIER = "DAWN";

    private final Cancel cancel;
    private final RandomGenerator random;

    /**
     * @param cancel 비율 · 몫
     * @param random 이 계획만의 난수원 — 주문 생성기와 나누지 않는다(나누면 취소를 켜는 것이 주문 내용을 바꾼다)
     */
    public CancelPlan(Cancel cancel, RandomGenerator random) {
        this.cancel = Objects.requireNonNull(cancel, "cancel");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * 다음 주문의 결정. 주문 순서대로, 주문마다 한 번 부른다.
     *
     * @param order 생성된 주문
     * @return 언제 취소하는가
     */
    public When next(GeneratedOrder order) {
        double target = random.nextDouble();
        double when = random.nextDouble();
        if (!TIER.equals(order.serviceTier()) || target >= cancel.ratio()) {
            return When.NONE;
        }
        return when < cancel.beforePlan() ? When.BEFORE_PLAN : When.AFTER_PUBLISH;
    }
}
