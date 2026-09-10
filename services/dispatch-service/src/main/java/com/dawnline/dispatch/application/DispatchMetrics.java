package com.dawnline.dispatch.application;

import com.dawnline.dispatch.domain.PlanMode;
import com.dawnline.dispatch.domain.PlanModeReason;
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
 * §9.1 의 계획·취소 메트릭.
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

    /**
     * 계획 결과 영속화 시간 (§6.7 목표 5,000건 ≤ 3초, ADR-029).
     *
     * <p>{@link #PLAN_DURATION} 과 <strong>한 쌍</strong>이다. 둘을 나눠 두는 것이 ADR-029 의
     * 요점이다 — 한 수치였을 때 30초 예산의 75% 를 ORM 이 쓰고 있는 것이 보이지 않았다.
     */
    public static final String PLAN_PERSIST = "dawnline.plan.persist";

    /** 계획 총비용. */
    public static final String PLAN_COST = "dawnline.plan.cost.krw";

    /** 미배정 주문 수 (§6.7 목표 ≤ 0.5%). */
    public static final String PLAN_UNASSIGNED = "dawnline.plan.unassigned";

    /**
     * <strong>열화</strong>로 돈 계획 수 (§6.7, ADR-034). 라벨 {@code camp}, {@code reason}.
     *
     * <p>운영자가 {@code mode=FAST} 를 지정한 계획은 <strong>세지 않는다</strong> — 사람이 고른
     * 것은 시스템이 밀려서 포기한 것이 아니다. 섞으면 이 값이 "성수기에 무엇을 포기했나" 가
     * 아니라 "누가 FAST 를 몇 번 썼나" 가 된다.
     */
    public static final String PLAN_DEGRADED = "dawnline.plan.degraded";

    /**
     * 랙을 <strong>모른 채</strong> 내린 자동 모드 판단의 수 (§6.7 첫 조건, ADR-034).
     *
     * <p>모름은 0 이 아니다. 이 값이 오르는 동안 열화 판단은 조건 <em>둘 중 하나만</em> 보고
     * 있고, 그 사실이 어디에도 안 보이면 "랙 조건이 한 번도 발화하지 않았다" 가 건강의 증거처럼
     * 읽힌다 — {@code dawnline_geo_lookups_total{outcome=bypassed}} 와 같은 어휘다.
     * <strong>폴백은 조용히 일어나면 안 된다.</strong>
     *
     * <p>정상적으로 오르는 경로도 있다: 운영자 재실행과 정체 회수는 레코드에서 오지 않으므로
     * 볼 파티션이 없다. 그래서 0 이어야 하는 값이 아니라 <em>비율</em>을 보는 값이다.
     */
    public static final String PLAN_BACKLOG_UNKNOWN = "dawnline.plan.backlog.unknown";

    /** 배송이 끝난 뒤 도착해 거부한 취소 (§6.10, §9.4 알림). */
    public static final String CANCEL_TOO_LATE = "dawnline.cancel.too_late";

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

        PlanModeReason reason = plan.modeReason().orElse(PlanModeReason.NONE);
        if (reason.isDegraded()) {
            // 열화가 보이지 않으면 "성수기에도 정시" 를 위해 무엇을 포기했는지 아무도 모른다.
            registry.counter(PLAN_DEGRADED, "camp", plan.campId().toString(),
                    "reason", reason.name()).increment();
        }
        if (reason == PlanModeReason.LAG_UNKNOWN) {
            registry.counter(PLAN_BACKLOG_UNKNOWN, "camp", plan.campId().toString()).increment();
        }
    }

    /**
     * 계획 결과를 저장하는 데 걸린 시간 (ADR-029).
     *
     * <p>라우트·stop·설명 저장과 outbox 기록까지다 — {@code planDurationMs} 가 끝나는
     * {@code finishedAt} 이후의 전부. 알고리즘이 아니라 I/O 를 재는 값이고, 그래서 §6.7 의
     * 30초와 견주지 않는다.
     *
     * @param campId   캠프 id
     * @param elapsed  걸린 시간
     */
    public void planPersisted(UUID campId, Duration elapsed) {
        Objects.requireNonNull(campId, "campId");
        Objects.requireNonNull(elapsed, "elapsed");
        Timer.builder(PLAN_PERSIST)
                .description("계획 결과 영속화 시간 (DESIGN.md §6.7, ADR-029)")
                .tag("camp", campId.toString())
                .register(registry)
                .record(elapsed);
    }

    /**
     * 배송이 끝난 뒤 도착한 취소를 거부했다 (§6.10 넷째 분기, ADR-026 결정 3).
     *
     * <p>이 값이 오른다는 것은 order-service 가 {@code order.dispatched} 를 배송 완료 시점까지
     * 소비하지 못했다는 뜻이다 — 정상이면 계획 발행과 기사 출발 사이가 분 단위 이상이다. 그래서
     * 이것은 이상 신호가 아니라 <strong>경합 창의 폭</strong>이고, order-service 의 축 밖 거부
     * 카운터와 한 쌍이다. 오르면 볼 곳은 dispatch 가 아니라 그쪽 컨슈머 랙이다.
     *
     * @param campId 캠프 id
     */
    public void cancelTooLate(UUID campId) {
        Objects.requireNonNull(campId, "campId");
        registry.counter(CANCEL_TOO_LATE, "camp", campId.toString()).increment();
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
