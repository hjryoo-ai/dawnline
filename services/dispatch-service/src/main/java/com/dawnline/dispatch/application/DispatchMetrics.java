package com.dawnline.dispatch.application;

import com.dawnline.dispatch.application.port.in.ReplanRouteUseCase;
import com.dawnline.dispatch.domain.PlanMode;
import com.dawnline.dispatch.domain.PlanModeReason;
import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.observability.DawnlineMeters;
import com.dawnline.observability.DawnlineMetric;
import com.dawnline.observability.DawnlineMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * §9.1 의 계획·취소 메트릭.
 *
 * <p>이름 · 타입 · 라벨은 카탈로그({@link DawnlineMetrics})에 있고 등록은 {@link DawnlineMeters} 가 한다(ADR-060).
 * 각 값의 뜻은 §9.1 의 행과 아래 메서드에 있다.
 *
 * <h2>게이지 값을 캠프마다 들고 있는 이유</h2>
 * 같은 이름 · 태그로 게이지를 다시 등록하면 레지스트리는 기존 미터를 돌려주고 새 상태 객체를 쓰지 않는다. 그래서
 * 캠프마다 상태를 하나씩 들고 값을 바꾼다. 상태는 헬퍼가 강한 참조로 잡는다 — 약한 참조였을 때 fulfillment 에서
 * 게이지가 조용히 {@code NaN} 이 된 적이 있다.
 *
 * <h2>{@code camp} 라벨이 코드가 아니라 id 인 이유</h2>
 * fulfillment 의 같은 이름 라벨은 캠프 <em>코드</em>({@code CAMP-SEO-N})를 쓴다. dispatch 는
 * 캠프의 참조 데이터를 갖지 않으므로(좌표만 {@code wave.closed} 스냅샷으로 받는다) 코드를 모른다.
 * id 를 쓰고, 사람이 읽는 이름이 필요하면 <strong>ops-api 가 붙인다</strong> — 코어 서비스로
 * 향하는 동기 호출이 허용되는 유일한 방향이다(불변규칙 4).
 */
public class DispatchMetrics {

    /** {@code dawnline_replan_total} 의 라벨 이름. */
    public static final String TAG_OUTCOME = "outcome";

    /**
     * {@code dawnline_plan_duration_seconds} 의 태그 — 계획이 <strong>어떻게 끝났나</strong>(§6.7, §6.9 재현 조건).
     * {@link #TERMINATION_CONVERGED} 면 할 일을 다 하고 끝났고 같은 입력이면 같은 결과다.
     * {@link #TERMINATION_DEADLINE} 이면 마감(§6.7)에 잘려 하지 못한 일이 있다 — 그 결과는 그날의
     * 기계 속도에 달렸다([ADR-036]). 시간은 러너를 따라 흔들리지만 이 값은 흔들리지 않는다.
     */
    public static final String TAG_TERMINATION = "termination";

    /** 수렴으로 끝났다. */
    public static final String TERMINATION_CONVERGED = "converged";

    /** 마감에 잘렸다. */
    public static final String TERMINATION_DEADLINE = "deadline";

    /** {@code dawnline_event_stale_total} 의 {@code consumer} 태그. */
    public static final String DELIVERY_STATUS_CONSUMER = "dispatch";

    /** {@code dawnline_event_stale_total} 의 {@code eventType} 태그. */
    public static final String DELIVERY_STATUS_EVENT_TYPE = "delivery.status";

    /** {@code dawnline_event_rejected_total} 의 {@code reason} 태그 — 모르는 상태값. */
    public static final String DELIVERY_STATUS_UNKNOWN_REASON = "unknown-delivery-status";

    private final MeterRegistry registry;
    private final Map<UUID, AtomicLong> costByCamp = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> unassignedByCamp = new ConcurrentHashMap<>();

    /**
     * @param registry 미터 레지스트리
     */
    public DispatchMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        // 다섯 갈래를 기동에서 등록한다. 「0 이다」와 「그런 지표가 없다」는 다른 말이고,
        // 재계획이 한 번도 돌지 않은 새벽에 대시보드가 그 둘을 구별하지 못하면 안 된다.
        for (ReplanRouteUseCase.Outcome outcome : ReplanRouteUseCase.Outcome.values()) {
            replanCounter(outcome);
        }
    }

    /**
     * 재계획 하나가 끝났다 (§6.8, [ADR-048] 결정 5).
     *
     * <p><strong>커밋 뒤에 부른다</strong> — 롤백된 재계획의 숫자가 남으면 그 차이는 장애 때
     * 가장 커진다.
     *
     * @param outcome 무엇을 했는가
     */
    public void replanned(ReplanRouteUseCase.Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        replanCounter(outcome).increment();
    }

    /**
     * 두 편차가 갈렸다 ([ADR-048] 결정 2).
     *
     * <p>tracking 이 본 편차와 dispatch 가 자기 테이블에서 계산한 편차의 차이다. 갈리는 것이
     * 정보이므로 <strong>버리지도 않고 입력으로 쓰지도 않는다</strong> — 센다.
     */
    public void atRiskDeviationMismatch() {
        DawnlineMeters.counter(registry, DawnlineMetrics.AT_RISK_DEVIATION_MISMATCH).increment();
    }

    private Counter replanCounter(ReplanRouteUseCase.Outcome outcome) {
        return DawnlineMeters.counter(registry, DawnlineMetrics.REPLAN, TAG_OUTCOME, outcome.label());
    }

    /**
     * 계획 하나가 끝났다.
     *
     * @param plan            발행까지 끝난 계획
     * @param budgetExhausted 마감 때문에 하지 못한 일이 있었는가 ({@code PlanResult#budgetExhausted})
     */
    public void planPublished(RoutePlan plan, boolean budgetExhausted) {
        Objects.requireNonNull(plan, "plan");
        String strategy = plan.strategy().orElse("unknown");
        PlanMode mode = plan.mode().orElse(PlanMode.FULL);

        DawnlineMeters.timer(registry, DawnlineMetrics.PLAN_DURATION,
                "strategy", strategy,
                "mode", mode.name(),
                TAG_TERMINATION, budgetExhausted ? TERMINATION_DEADLINE : TERMINATION_CONVERGED)
                .record(Duration.ofMillis(plan.planDurationMs().orElse(0)));

        gauge(costByCamp, DawnlineMetrics.PLAN_COST, plan.campId(), plan.totalCost().map(c -> c.krw()).orElse(0L));
        gauge(unassignedByCamp, DawnlineMetrics.PLAN_UNASSIGNED, plan.campId(),
                plan.unassignedCount().orElse(0).longValue());

        PlanModeReason reason = plan.modeReason().orElse(PlanModeReason.NONE);
        if (reason.isDegraded()) {
            // 열화가 보이지 않으면 "성수기에도 정시" 를 위해 무엇을 포기했는지 아무도 모른다.
            DawnlineMeters.counter(registry, DawnlineMetrics.PLAN_DEGRADED,
                    "camp", plan.campId().toString(), "reason", reason.name()).increment();
        }
        if (reason == PlanModeReason.LAG_UNKNOWN) {
            DawnlineMeters.counter(registry, DawnlineMetrics.PLAN_BACKLOG_UNKNOWN, "camp", plan.campId().toString())
                    .increment();
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
        DawnlineMeters.timer(registry, DawnlineMetrics.PLAN_PERSIST, "camp", campId.toString())
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
        DawnlineMeters.counter(registry, DawnlineMetrics.CANCEL_TOO_LATE, "camp", campId.toString()).increment();
    }

    /**
     * <strong>어느 라우트에도</strong> 그 주문의 stop 이 없거나, 이미 지나온 단계로 되돌아가는
     * {@code delivery.status} 를 무시했다 (§9.1, ADR-047 결정 2·5).
     *
     * <p>「이 라우트에 없다」가 아니라 「어디에도 없다」인 것이 결정 2 다. 전자로 세면 재계획이
     * 주문을 옮긴 뒤 도착한 <em>사실</em>이 전부 여기로 들어오고, 그것들은 버려서는 안 되는
     * 것들이다 — {@link #statusAfterRelocate(int)} 가 그 자리다.
     *
     * <p><strong>커밋(또는 적재) 뒤에 부른다.</strong> 롤백된 작업의 숫자가 남으면 그 차이는
     * 장애 때 가장 커진다 — 지표가 가장 많이 읽히는 순간에 가장 많이 틀린다.
     *
     * @param count 이번 이벤트에서 무시한 수
     */
    public void deliveryStatusStale(int count) {
        if (count <= 0) {
            return;
        }
        DawnlineMeters.counter(registry, DawnlineMetrics.EVENT_STALE,
                MessagingMetrics.TAG_CONSUMER, DELIVERY_STATUS_CONSUMER,
                MessagingMetrics.TAG_EVENT_TYPE, DELIVERY_STATUS_EVENT_TYPE)
                .increment(count);
    }

    /**
     * 취소된 stop 에 도착한 상태 보고를 무시했다 (§9.1, ADR-047 결정 4).
     *
     * <p>{@link #deliveryStatusStale(int)} 와 같은 시점 규칙이다.
     *
     * @param count 이번 이벤트에서 무시한 수
     */
    public void scanAfterCancel(int count) {
        if (count <= 0) {
            return;
        }
        DawnlineMeters.counter(registry, DawnlineMetrics.SCAN_AFTER_CANCEL).increment(count);
    }

    /**
     * 이벤트의 라우트가 아닌 곳에서 stop 을 찾아 적용했다 (§9.1, ADR-047 결정 2).
     *
     * <p>{@link #deliveryStatusStale(int)} 와 같은 시점 규칙이다 — 적용한 <strong>뒤</strong>에
     * 센다.
     *
     * @param count 이번 이벤트에서 옮겨 적용한 수
     */
    public void statusAfterRelocate(int count) {
        if (count <= 0) {
            return;
        }
        DawnlineMeters.counter(registry, DawnlineMetrics.STATUS_AFTER_RELOCATE).increment(count);
    }

    /**
     * 계약이 새 {@code status} 값을 냈고 이쪽이 아직 모른다 (§4.7, §4.6 3행).
     *
     * <p>{@code dawnline_event_stale_total} 이 아니라 {@code rejected} 로 세는 이유: stale 은
     * <strong>늘 조금씩 있는 값</strong>이라 거기 섞으면 이 사실이 묻힌다. 이것은 사람이 봐야
     * 하는 상황이다 — 새 값을 내는 발행자가 배포됐다는 뜻이다.
     */
    public void deliveryStatusUnknown() {
        DawnlineMeters.counter(registry, DawnlineMetrics.EVENT_REJECTED,
                MessagingMetrics.TAG_CONSUMER, DELIVERY_STATUS_CONSUMER,
                MessagingMetrics.TAG_EVENT_TYPE, DELIVERY_STATUS_EVENT_TYPE,
                MessagingMetrics.TAG_REASON, DELIVERY_STATUS_UNKNOWN_REASON)
                .increment();
    }

    private void gauge(Map<UUID, AtomicLong> holder, DawnlineMetric metric, UUID campId, long value) {
        holder.computeIfAbsent(campId, camp -> {
            AtomicLong slot = new AtomicLong();
            DawnlineMeters.gauge(registry, metric, slot, AtomicLong::doubleValue, "camp", camp.toString());
            return slot;
        }).set(value);
    }
}
