package com.dawnline.dispatch.application;

import com.dawnline.dispatch.domain.PlanMode;
import com.dawnline.dispatch.domain.RoutePlan;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * §9.1 의 계획 메트릭 넷.
 *
 * <h2>게이지 값을 직접 들고 있는 이유</h2>
 * {@code registry.gauge} 는 재등록 시 기존 미터를 돌려주고 대상을 <strong>약한 참조</strong>로
 * 든다. 값을 들고 있지 않으면 게이지가 조용히 {@code NaN} 이 된다 — fulfillment 에서 겪었다.
 *
 * <h2>{@code camp} 라벨이 코드가 아니라 id 인 이유</h2>
 * fulfillment 의 같은 이름 라벨은 캠프 <em>코드</em>({@code CAMP-SEO-N})를 쓴다. dispatch 는
 * 캠프의 참조 데이터를 갖지 않으므로(좌표만 {@code wave.closed} 스냅샷으로 받는다) 코드를 모른다.
 * id 를 쓰고, 사람이 읽는 이름이 필요하면 <strong>ops-api 가 붙인다</strong> — 코어 서비스로
 * 향하는 동기 호출이 허용되는 유일한 방향이다(불변규칙 4).
 */
public class DispatchMetrics {

    /** 계획 소요 시간 (§6.7 목표 p95 ≤ 30초). */
    public static final String PLAN_DURATION = "dawnline.plan.duration";

    /** 계획 총비용. */
    public static final String PLAN_COST = "dawnline.plan.cost.krw";

    /** 미배정 주문 수 (§6.7 목표 ≤ 0.5%). */
    public static final String PLAN_UNASSIGNED = "dawnline.plan.unassigned";

    /** 열화 모드로 돈 계획 수 (§6.7). */
    public static final String PLAN_DEGRADED = "dawnline.plan.degraded";

    private final MeterRegistry registry;
    private final Map<UUID, AtomicLong> costByCamp = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> unassignedByCamp = new ConcurrentHashMap<>();

    /**
     * @param registry 미터 레지스트리
     */
    public DispatchMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * 계획 하나가 끝났다.
     *
     * @param plan 발행까지 끝난 계획
     */
    public void planPublished(RoutePlan plan) {
        Objects.requireNonNull(plan, "plan");
        String strategy = plan.strategy().orElse("unknown");
        PlanMode mode = plan.mode().orElse(PlanMode.FULL);

        Timer.builder(PLAN_DURATION)
                .description("계획 소요 시간 (DESIGN.md §6.7)")
                .tag("strategy", strategy)
                .tag("mode", mode.name())
                .register(registry)
                .record(Duration.ofMillis(plan.planDurationMs().orElse(0)));

        gauge(costByCamp, PLAN_COST, plan.campId(), plan.totalCost().map(c -> c.krw()).orElse(0L));
        gauge(unassignedByCamp, PLAN_UNASSIGNED, plan.campId(),
                plan.unassignedCount().orElse(0).longValue());

        if (mode == PlanMode.FAST) {
            // 열화가 보이지 않으면 "성수기에도 정시" 를 위해 무엇을 포기했는지 아무도 모른다.
            registry.counter(PLAN_DEGRADED, "camp", plan.campId().toString()).increment();
        }
    }

    private void gauge(Map<UUID, AtomicLong> holder, String name, UUID campId, long value) {
        holder.computeIfAbsent(campId, camp -> {
            AtomicLong slot = new AtomicLong();
            registry.gauge(name, io.micrometer.core.instrument.Tags.of("camp", camp.toString()),
                    slot, AtomicLong::doubleValue);
            return slot;
        }).set(value);
    }
}
