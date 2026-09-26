package com.dawnline.observability;

import static com.dawnline.observability.DawnlineMetric.Label.closed;
import static com.dawnline.observability.DawnlineMetric.Label.open;

import com.dawnline.observability.DawnlineMetric.Label;
import com.dawnline.observability.DawnlineMetric.Type;
import java.util.List;

/**
 * 메트릭 카탈로그 — {@code docs/DESIGN.md} §9.1 표의 행마다 항목 하나 (ADR-060 결정 1).
 *
 * <p>이 클래스가 이름의 유일한 자리다. 서비스는 여기의 항목으로만 미터를 등록하고({@link DawnlineMeters}, ArchUnit 규칙 11),
 * {@code DawnlineMetricsTest} 가 §9.1 을 읽어 이름 · 타입 · 라벨 집합을 양방향으로 대조한다. 행을 더하면 이 클래스와
 * 표가 같은 PR 에서 맞아야 초록이다.
 *
 * <h2>Micrometer 이름을 바꾸지 않은 이유</h2>
 * 대부분 점 표기({@code dawnline.orders.placed})이고 몇은 밑줄이다({@code dawnline_geo_index_loaded}). Prometheus 레지스트리는
 * 둘 다 표의 이름으로 낸다 — 점은 밑줄이 되고, 카운터는 {@code _total}, 타이머는 {@code _seconds} 가 붙는다. 이름 대응은 주석이
 * 아니라 레지스트리가 말한다: 테스트가 항목마다 실제로 등록해 긁어 본다.
 *
 * <h2>닫힌 라벨의 값</h2>
 * 값이 enum 에서 오는 라벨은 그 enum 과 이 목록을 대조하는 테스트가 짝으로 있다(ADR-060 결정 2) — enum 에 값이 느는 날
 * 운영의 첫 등록이 아니라 빌드가 실패한다.
 */
public final class DawnlineMetrics {

    private DawnlineMetrics() {
        throw new AssertionError("상수 홀더입니다. 인스턴스를 만들지 마세요.");
    }

    /** 서비스 티어 — order · fulfillment 의 {@code ServiceTier} 전부(두 서비스의 enum 과 각자의 테스트가 대조한다). */
    private static final String[] TIERS = {"DAWN", "SAME_DAY", "NEXT_DAY"};

    // --- order-service -------------------------------------------------------

    /** 접수에 성공한 주문. 멱등 재생은 세지 않는다(§5.1). */
    public static final DawnlineMetric ORDERS_PLACED = counter("dawnline_orders_placed_total",
            "dawnline.orders.placed", "접수된 주문 수 — 멱등 재생은 세지 않는다",
            closed("tier", TIERS));

    /** 같은 멱등 키의 재요청으로 저장된 응답을 재생한 횟수. */
    public static final DawnlineMetric IDEMPOTENT_REPLAYS = counter("dawnline_idempotent_replays_total",
            "dawnline.idempotent.replays", "멱등 재생 횟수 — 저장된 응답을 다시 준 횟수",
            closed("tier", TIERS));

    /** 레이트 리밋 판정. {@code bypassed} 는 Redis 장애로 판정을 건너뛴 것(§7.2). */
    public static final DawnlineMetric RATE_LIMIT_DECISIONS = counter("dawnline_rate_limit_decisions_total",
            "dawnline.rate.limit.decisions", "고객별 레이트 리밋 판정 — bypassed 는 Redis 장애로 건너뛴 것",
            closed("outcome", "allowed", "limited", "bypassed"));

    // --- outbox (전 서비스, libs/messaging) -----------------------------------

    /** 가장 오래된 미발행 outbox 행의 나이(초). */
    public static final DawnlineMetric OUTBOX_LAG_SECONDS = gauge("dawnline_outbox_lag_seconds",
            "dawnline.outbox.lag.seconds", "가장 오래된 미발행 outbox 행이 만들어진 뒤 흐른 초",
            open("service"));

    /** 미발행 outbox 행 수(격리 행 제외). */
    public static final DawnlineMetric OUTBOX_UNPUBLISHED = gauge("dawnline_outbox_unpublished",
            "dawnline.outbox.unpublished", "아직 Kafka 로 발행되지 않은 outbox 행 수",
            open("service"));

    /** 격리된(미해결) outbox 행 수(§4.6). */
    public static final DawnlineMetric OUTBOX_FAILED = gauge("dawnline_outbox_failed",
            "dawnline.outbox.failed", "결정적 실패로 격리된 outbox 행 수 — 0 이 아니면 사람이 본다(RB-05)",
            open("service"));

    /** 릴레이 리더십 — 1 리더 · 0 팔로워 · -1 판정 불가(ADR-027). */
    public static final DawnlineMetric OUTBOX_LEADER = gauge("dawnline_outbox_leader",
            "dawnline.outbox.leader", "릴레이 리더십 — 1 리더 · 0 팔로워(정상) · -1 판정 불가(DB 세션 장애)",
            open("service"));

    // --- 소비자 (전 소비자, libs/messaging) -----------------------------------

    /** 멱등 소비자의 결과(§4.4 · §4.6). */
    public static final DawnlineMetric EVENT_PROCESSED = counter("dawnline_event_processed_total",
            "dawnline.event.processed", "이벤트 소비 결과",
            open("consumer"), open("eventType"),
            closed("outcome", "ok", "dup", "rejected", "dlq", "replay_not_target"));

    /** 비즈니스 규칙 위반으로 무시한 이벤트 — 「왜」(§4.6). */
    public static final DawnlineMetric EVENT_REJECTED = counter("dawnline_event_rejected_total",
            "dawnline.event.rejected", "비즈니스 규칙 위반으로 무시한 이벤트 (DLQ 아님)",
            open("consumer"), open("eventType"), open("reason"));

    /** 순서 역전을 흡수하느라 무시한 이벤트 — 설계된 동작(ADR-017 · ADR-045 · ADR-047). */
    public static final DawnlineMetric EVENT_STALE = counter("dawnline_event_stale_total",
            "dawnline.event.stale", "순서 역전을 흡수하느라 무시한 이벤트 — 설계된 동작",
            open("consumer"), open("eventType"));

    /**
     * 재시도 경로에 든 실패한 배달 — 「몇 번 · 왜」(ADR-015 후속 정정). {@code reason} 은 소비 측 경계표의 행이고
     * {@code ConsumeFailure.retryReasons()} 와 대조된다(libs/messaging 의 테스트).
     */
    public static final DawnlineMetric EVENT_RETRY = counter("dawnline_event_retry_total",
            "dawnline.event.retry", "재시도 경로에 든 실패한 배달 — reason 은 소비 측 경계표의 행(ADR-015 후속 정정)",
            open("consumer"), closed("reason", "redis", "db_connection", "db_resource", "db_transient", "db_integrity",
                    "argument", "domain", "other"));

    /** 지금 재시도 중인 레코드가 파티션을 막고 있는 시간 — 「얼마나 오래」(ADR-015 후속 정정). 재시도 중이 아니면 0. */
    public static final DawnlineMetric EVENT_RETRY_AGE_SECONDS = gauge("dawnline_event_retry_age_seconds",
            "dawnline.event.retry.age.seconds", "지금 재시도 중인 레코드가 파티션을 막고 있는 초, 파티션 중 최대 — 아니면 0",
            open("consumer"));

    // --- fulfillment-service -------------------------------------------------

    /** 마감 시점의 편입 주문 수(ADR-025). */
    public static final DawnlineMetric WAVE_ORDERS = gauge("dawnline_wave_orders",
            "dawnline.wave.orders", "마감 시점에 웨이브에 편입된 주문 수",
            open("camp"), closed("tier", TIERS));

    /** 홈 FC 가 필터에서 떨어져 대체 FC 를 고른 횟수(ADR-021). */
    public static final DawnlineMetric FC_FALLBACK = counter("dawnline_fc_fallback_total",
            "dawnline.fc.fallback", "캠프의 홈 FC 가 필터에서 떨어져 대체 FC 를 고른 횟수",
            open("camp"), closed("reason", "tier", "cold", "inventory"));

    /** 하류가 상류의 약속을 개정한 횟수(ADR-020 · ADR-054). */
    public static final DawnlineMetric PROMISE_REVISED = counter("dawnline_promise_revised_total",
            "dawnline.promise.revised", "하류가 상류의 약속을 개정한 횟수 — cause 는 원래 컷오프 웨이브를 누가 닫았나",
            open("camp"), closed("tier", TIERS), closed("cause", "scheduled", "manual", "unknown"));

    /** Redis GEO 적재 성공 여부 0/1(ADR-016 후속 정정). */
    public static final DawnlineMetric GEO_INDEX_LOADED = gauge("dawnline_geo_index_loaded",
            "dawnline_geo_index_loaded", "Redis GEO 적재 성공 여부 0/1 — 0 이어도 서비스는 폴백으로 동작한다",
            closed("index", "fc", "camp"));

    /** fulfillment 의 Redis 조회를 Redis 로 답했나, DB 폴백으로 답했나(§7.2) — 이름과 달리 GEO 만이 아니다. */
    public static final DawnlineMetric GEO_LOOKUPS = counter("dawnline_geo_lookups_total",
            "dawnline_geo_lookups_total", "fulfillment 의 Redis 조회(FC 거리 · 권역 캐시 · 웨이브 락) — bypassed 는 DB 로 답한 것",
            closed("index", "fc", "zone", "wave_lock"), closed("outcome", "redis", "bypassed"));

    /** 보존 기간(30일)을 넘긴 계획되지 않은 {@code fulfillment_orders}(ADR-058 결정 8). */
    public static final DawnlineMetric FULFILLMENT_ORDERS_STUCK = gauge("dawnline_fulfillment_orders_stuck",
            "dawnline.fulfillment.orders.stuck", "보존 기간을 넘겼는데 웨이브가 아직 계획되지 않은 주문 수 — 세기 전 · 실패 중 NaN");

    // --- dispatch-service ----------------------------------------------------

    /** 계획의 알고리즘 시간 — 버킷(§6.7, ADR-036). */
    public static final DawnlineMetric PLAN_DURATION = histogram("dawnline_plan_duration_seconds",
            "dawnline.plan.duration", "계획 소요 시간(알고리즘만) — termination=deadline 이면 마감에 잘렸다",
            open("strategy"), closed("mode", "FULL", "FAST"), closed("termination", "converged", "deadline"));

    /** 계획 결과 영속화 시간(ADR-029). */
    public static final DawnlineMetric PLAN_PERSIST = histogram("dawnline_plan_persist_seconds",
            "dawnline.plan.persist", "계획 결과(라우트 · stop · 설명 · outbox) 저장 시간",
            open("camp"));

    /** 계획 결과 총비용(원). */
    public static final DawnlineMetric PLAN_COST = gauge("dawnline_plan_cost_krw",
            "dawnline.plan.cost.krw", "마지막 계획의 총비용(원)",
            open("camp"));

    /** 계획에서 배정되지 못한 주문 수. */
    public static final DawnlineMetric PLAN_UNASSIGNED = gauge("dawnline_plan_unassigned",
            "dawnline.plan.unassigned", "마지막 계획에서 배정되지 못한 주문 수",
            open("camp"));

    /** 자동 열화로 돈 계획(ADR-034). 운영자가 고른 FAST 는 세지 않는다. */
    public static final DawnlineMetric PLAN_DEGRADED = counter("dawnline_plan_degraded_total",
            "dawnline.plan.degraded", "자동 열화로 돈 계획 — 운영자가 고른 FAST 는 세지 않는다",
            open("camp"), closed("reason", "LAG", "BUDGET"));

    /** 랙을 모른 채 내린 자동 모드 판단(§6.7). */
    public static final DawnlineMetric PLAN_BACKLOG_UNKNOWN = counter("dawnline_plan_backlog_unknown_total",
            "dawnline.plan.backlog.unknown", "랙을 모른 채 내린 자동 모드 판단 — 모름은 0 이 아니다",
            open("camp"));

    /** 배송이 끝난 뒤 도착해 거부한 취소(§6.10, ADR-026). */
    public static final DawnlineMetric CANCEL_TOO_LATE = counter("dawnline_cancel_too_late_total",
            "dawnline.cancel.too_late", "이미 도착 · 완료된 stop 에 도착해 거부한 order.cancelled",
            open("camp"));

    /** 다른 라우트의 stop 에 적용한 {@code delivery.status}(ADR-047 결정 2). */
    public static final DawnlineMetric STATUS_AFTER_RELOCATE = counter("dawnline_status_after_relocate_total",
            "dawnline.status.after.relocate", "이벤트가 말한 라우트가 아니라 다른 라우트의 stop 에 적용한 delivery.status");

    /** 부분 재계획이 트리거 하나에 한 일(ADR-048 결정 5) — 합이 트리거 수다. */
    public static final DawnlineMetric REPLAN = counter("dawnline_replan_total",
            "dawnline.replan", "§6.8 부분 재계획의 결과 — 다섯 갈래의 합이 트리거 수다",
            closed("outcome", "applied", "cooldown", "no-anchor", "no-candidate", "no-gain"));

    /** 두 편차가 60초 넘게 갈린 횟수(ADR-048 결정 2). */
    public static final DawnlineMetric AT_RISK_DEVIATION_MISMATCH = counter(
            "dawnline_at_risk_deviation_mismatch_total", "dawnline.at_risk.deviation.mismatch",
            "dispatch 가 계산한 편차와 delivery.at-risk 의 편차가 허용 오차를 넘게 갈린 횟수");

    /** 30일을 넘긴 종결되지 않은 계획(ADR-059 결정 4). */
    public static final DawnlineMetric ROUTE_PLANS_STUCK = gauge("dawnline_route_plans_stuck",
            "dawnline.route.plans.stuck", "30일을 넘겼는데 종결이 아닌 계획 수 — 세기 전 · 실패 중 NaN");

    // --- tracking-service (+ dispatch) -----------------------------------------

    /** 발행한 {@code delivery.at-risk}. */
    public static final DawnlineMetric AT_RISK = counter("dawnline_at_risk_total",
            "dawnline.at.risk", "발행한 delivery.at-risk — 「위험한 라우트 수」가 아니라 「알린 횟수」",
            open("camp"));

    /** 쿨다운을 쓰지 못해 그냥 발행한 횟수(§7.2 fail-open). */
    public static final DawnlineMetric AT_RISK_COOLDOWN_BYPASSED = counter("dawnline_at_risk_cooldown_bypassed_total",
            "dawnline.at.risk.cooldown.bypassed", "at-risk 쿨다운(Redis)을 쓰지 못해 쿨다운 없이 발행한 횟수");

    /** 취소된 배송(tracking) · 취소된 stop(dispatch)에 도착한 스캔 — 자리는 job 으로 갈린다. */
    public static final DawnlineMetric SCAN_AFTER_CANCEL = counter("dawnline_scan_after_cancel_total",
            "dawnline.scan.after.cancel", "취소된 배송 · stop 에 도착해 무시한 스캔 — tracking 과 dispatch 가 같은 이름으로 센다");

    /** 기사가 찍은 자리가 지금 아는 자리와 다른 스캔(ADR-047 결정 1). */
    public static final DawnlineMetric SCAN_AFTER_RELOCATE = counter("dawnline_scan_after_relocate_total",
            "dawnline.scan.after.relocate", "기사가 찍은 (routeId, stopSeq) 가 tracking 이 아는 자리와 다른 스캔");

    /** 앞으로 덮인 {@code shipment_events} 일 파티션 수(§5.4). */
    public static final DawnlineMetric SHIPMENT_PARTITIONS_AHEAD = gauge("dawnline_shipment_partitions_ahead",
            "dawnline_shipment_partitions_ahead", "오늘을 포함해 앞으로 덮인 shipment_events 일 파티션 수 — 0 에서 스캔이 실패한다");

    // --- ops-api -------------------------------------------------------------

    /** 정시 배송률 — 두 기준을 따로(§8.1). */
    public static final DawnlineMetric DELIVERY_ON_TIME_RATIO = gauge("dawnline_delivery_on_time_ratio",
            "dawnline.delivery.on.time.ratio", "정시 배송률 — 현재 버킷 포함 UTC 정시 버킷 24개, 결과가 없거나 갱신 실패 중 NaN",
            open("camp"), closed("basis", "promised", "revised"));

    /** 정시율의 모집단 밖으로 빠진 결과(§5.5). */
    public static final DawnlineMetric KPI_EXCLUDED = gauge("dawnline_kpi_excluded",
            "dawnline.kpi.excluded", "정시율에서 빠진 결과 — 약속(또는 캠프)을 아직 모르는 완료 · 실패",
            closed("reason", "promise_unknown"));

    /** 정시율과 같은 창 · 같은 스냅숏의 결과 수 — 정시율의 분모를 둘로 편 것(§5.5). */
    public static final DawnlineMetric KPI_DELIVERY = gauge("dawnline_kpi_delivery",
            "dawnline.kpi.delivery", "정시율과 같은 창의 결과 수 — 결과가 없는 캠프는 0, 갱신 실패 중 NaN",
            open("camp"), closed("outcome", "completed", "failed"));

    /** {@code rm_routes} 의 진행 집계 — 라우트 단위가 아니다(routeId 는 열린 라벨). */
    public static final DawnlineMetric ROUTES = gauge("dawnline_routes",
            "dawnline.routes", "캠프 · 진행별 라우트 수 — KPI 와 같은 갱신, 갱신 실패 중 NaN",
            open("camp"), closed("status", "assigned", "in_progress", "completed", "void", "unknown"));

    /** 운영자 커맨드의 결과 — 커밋한 뒤에 센다. */
    public static final DawnlineMetric OPS_COMMANDS = counter("dawnline_ops_commands_total",
            "dawnline.ops.commands", "운영자 커맨드 — 감사 행의 결과를 커밋한 뒤에 센다",
            closed("action", "RUN_PLAN", "REASSIGN_STOP", "CANCEL_ORDER", "CLOSE_WAVE", "REQUEUE_OUTBOX", "DLQ_REPLAY",
                    "RESOLVE_AUDIT"),
            closed("result", "SUCCEEDED", "REJECTED", "FAILED", "UNKNOWN"));

    /** 마지막으로 성공한 KPI 갱신 뒤로 흐른 초. */
    public static final DawnlineMetric KPI_REFRESH_AGE = gauge("dawnline_kpi_refresh_age_seconds",
            "dawnline.kpi.refresh.age.seconds", "마지막으로 성공한 KPI 갱신 뒤로 흐른 초 — 갱신이 멈추면 커진다");

    /** 보존 기간을 넘긴 종결되지 않은 {@code rm_orders}(ADR-058 결정 3). */
    public static final DawnlineMetric RM_ORDERS_STUCK = gauge("dawnline_rm_orders_stuck",
            "dawnline.rm.orders.stuck", "보존 기간을 넘겼는데 종결이 아닌 rm_orders 행 수 — 세기 전 · 실패 중 NaN");

    // --- 코어 넷 (libs/web) ----------------------------------------------------

    /** 내부 토큰 없이 · 틀린 토큰으로 들어와 401 을 받은 운영자 쓰기(ADR-055). */
    public static final DawnlineMetric INTERNAL_TOKEN_REJECTED = counter("dawnline_internal_token_rejected_total",
            "dawnline.internal.token.rejected", "내부 토큰 없이 · 틀린 토큰으로 들어와 401 을 받은 운영자 쓰기",
            closed("reason", "missing", "mismatch"));

    // --- 정리를 가진 전 서비스 -------------------------------------------------

    /** 그 표의 정리가 마지막으로 성공한 뒤로 흐른 초(ADR-058 결정 6). */
    public static final DawnlineMetric RETENTION_LAST_SUCCESS_AGE = gauge(
            "dawnline_retention_last_success_age_seconds", "dawnline.retention.last.success.age.seconds",
            "그 표의 정리가 마지막으로 성공한 뒤로 흐른 초 — 성공한 적이 없으면 기동부터",
            open("table"));

    // --- 시계 (전 서비스, libs/messaging) --------------------------------------

    /** 주입 시계의 오프셋(초) — 0 이 아니면 시뮬레이션이다(ADR-066). 서비스 다섯이 같은 값이어야 한다({@code make obs-check}). */
    public static final DawnlineMetric CLOCK_OFFSET = gauge("dawnline_clock_offset_seconds",
            "dawnline.clock.offset.seconds", "주입 시계가 벽시계보다 앞선 초 — 0 이 아니면 시뮬레이션 시각이다(ADR-066)",
            open("service"));

    /**
     * 카탈로그 전부 — §9.1 의 행 수와 같다. 상수만 더하고 이 목록을 잊으면 {@code DawnlineMetricsTest} 가 리플렉션으로 잡는다.
     */
    public static final List<DawnlineMetric> ALL = List.of(
            ORDERS_PLACED, IDEMPOTENT_REPLAYS, RATE_LIMIT_DECISIONS,
            OUTBOX_LAG_SECONDS, OUTBOX_UNPUBLISHED, OUTBOX_FAILED, OUTBOX_LEADER,
            EVENT_PROCESSED, EVENT_REJECTED, EVENT_STALE, EVENT_RETRY, EVENT_RETRY_AGE_SECONDS,
            WAVE_ORDERS, FC_FALLBACK, PROMISE_REVISED, GEO_INDEX_LOADED, GEO_LOOKUPS, FULFILLMENT_ORDERS_STUCK,
            PLAN_DURATION, PLAN_PERSIST, PLAN_COST, PLAN_UNASSIGNED, PLAN_DEGRADED, PLAN_BACKLOG_UNKNOWN,
            CANCEL_TOO_LATE, STATUS_AFTER_RELOCATE, REPLAN, AT_RISK_DEVIATION_MISMATCH, ROUTE_PLANS_STUCK,
            AT_RISK, AT_RISK_COOLDOWN_BYPASSED, SCAN_AFTER_CANCEL, SCAN_AFTER_RELOCATE, SHIPMENT_PARTITIONS_AHEAD,
            DELIVERY_ON_TIME_RATIO, KPI_EXCLUDED, KPI_DELIVERY, ROUTES, OPS_COMMANDS, KPI_REFRESH_AGE, RM_ORDERS_STUCK,
            INTERNAL_TOKEN_REJECTED,
            RETENTION_LAST_SUCCESS_AGE, CLOCK_OFFSET);

    private static DawnlineMetric counter(String name, String meterName, String help, Label... labels) {
        return new DawnlineMetric(name, Type.COUNTER, meterName, help, List.of(labels));
    }

    private static DawnlineMetric gauge(String name, String meterName, String help, Label... labels) {
        return new DawnlineMetric(name, Type.GAUGE, meterName, help, List.of(labels));
    }

    private static DawnlineMetric histogram(String name, String meterName, String help, Label... labels) {
        return new DawnlineMetric(name, Type.HISTOGRAM, meterName, help, List.of(labels));
    }
}
