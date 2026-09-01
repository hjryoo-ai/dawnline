package com.dawnline.observability;

import java.util.List;

/**
 * Dawnline 커스텀 메트릭 이름 상수 (DESIGN.md §9.1 표 전체).
 *
 * <p>목적은 <strong>오타로 인한 메트릭 분열 방지</strong>다. 서비스 코드에서 문자열
 * 리터럴로 메트릭을 만들지 말고 반드시 이 상수를 쓴다. 이름 하나가 서비스마다 다르게
 * 적히면 대시보드(§9.4)와 알림 규칙이 조용히 깨진다.
 *
 * <h2>이름 규칙 — 왜 점(.)이 아니라 밑줄(_)인가</h2>
 * <p>Micrometer 의 관례는 {@code dawnline.orders.placed} 같은 점 표기지만, 설계서 §9.1 은
 * Prometheus 노출 이름을 그대로 표로 정의했다. 설계서가 진실의 원천이므로(CLAUDE.md)
 * 여기서는 표의 이름을 <strong>미터 ID 로 그대로</strong> 쓴다. Prometheus 레지스트리를
 * 통과해도 이름이 그대로 유지된다는 것을 실제 구현으로 확인했다:
 * <ul>
 *   <li>{@code io.micrometer.prometheusmetrics.PrometheusNamingConvention#name} 은
 *       마지막에 {@code PrometheusNaming.sanitizeMetricName(...)} 을 호출하고, 이 함수는
 *       {@code _total} 같은 예약 접미사를 <em>떼어낸다</em>.</li>
 *   <li>Prometheus 클라이언트(1.x)의 카운터 라이터가 스크랩 시점에 {@code _total} 을
 *       <em>다시 붙인다</em>. 결과적으로 {@code dawnline_orders_placed_total} 이 그대로 노출된다.</li>
 *   <li>{@code _seconds} 는 예약 접미사가 아니고, 같은 접미사를 중복해서 붙이지 않도록
 *       구현이 방어한다. 따라서 {@code dawnline_plan_duration_seconds} 도 그대로 노출된다.</li>
 * </ul>
 *
 * <h2>타입 표기</h2>
 * <p>각 상수 주석의 타입은 §9.1 표 그대로다. 구현 시 매핑은 다음과 같다.
 * <ul>
 *   <li>counter → {@code Counter}</li>
 *   <li>gauge → {@code Gauge} (관측 함수 등록. 값을 직접 set 하지 않는다)</li>
 *   <li>histogram → {@code Timer} + {@code publishPercentileHistogram()}
 *       (또는 {@code management.metrics.distribution.percentiles-histogram} 프로퍼티)</li>
 * </ul>
 *
 * <p>라벨(태그) 키는 {@link MetricTags} 상수를 쓴다. 라벨 값은 반드시 저카디널리티여야 한다 —
 * 주문 ID·라우트 ID 처럼 무한히 늘어나는 값을 라벨로 쓰면 안 된다(그런 값은 로그 MDC 로,
 * {@link MdcKeys} 참고).
 */
public final class DawnlineMetrics {

    private DawnlineMetrics() {
        throw new AssertionError("상수 홀더입니다. 인스턴스를 만들지 마세요.");
    }

    // --- order-service -------------------------------------------------------

    /**
     * counter · 라벨: {@link MetricTags#TIER}, {@link MetricTags#CAMP}.
     * 접수에 성공한 주문 수. 멱등 재요청으로 기존 주문을 되돌려준 경우는 세지 않는다(§5.1).
     */
    public static final String ORDERS_PLACED_TOTAL = "dawnline_orders_placed_total";

    // --- outbox (모든 서비스 공통, libs/messaging 이 발행) --------------------

    /**
     * gauge · 라벨: {@link MetricTags#SERVICE}.
     * 가장 오래된 미발행 outbox 레코드의 경과 시간(초). 알림 임계값 30초(§9.4).
     */
    public static final String OUTBOX_LAG_SECONDS = "dawnline_outbox_lag_seconds";

    /**
     * gauge · 라벨: {@link MetricTags#SERVICE}.
     * 미발행 outbox 레코드 건수. 백프레셔·릴레이 정체 판단용(§8.3).
     */
    public static final String OUTBOX_UNPUBLISHED = "dawnline_outbox_unpublished";

    /**
     * counter · 라벨: {@link MetricTags#CONSUMER}, {@link MetricTags#EVENT_TYPE},
     * {@link MetricTags#OUTCOME}({@link MetricTags#OUTCOME_OK}/{@link MetricTags#OUTCOME_DUP}/
     * {@link MetricTags#OUTCOME_REJECTED}/{@link MetricTags#OUTCOME_DLQ}).
     * 멱등 소비자가 처리한 이벤트 수. {@code dup} 비율이 at-least-once 재전달의 증거다(§4.4).
     */
    public static final String EVENT_PROCESSED_TOTAL = "dawnline_event_processed_total";

    // --- fulfillment-service -------------------------------------------------

    /**
     * gauge · 라벨: {@link MetricTags#CAMP}, {@link MetricTags#TIER}.
     * 현재 열려 있는 웨이브에 편입된 주문 수(§5.2).
     */
    public static final String WAVE_ORDERS = "dawnline_wave_orders";

    // --- dispatch-service ----------------------------------------------------

    /**
     * histogram · 라벨: {@link MetricTags#STRATEGY}, {@link MetricTags#MODE}.
     * 계획 1회 실행 시간(초). SLO: 5,000 주문 p95 ≤ 30초(§8.1), 알림 p95 &gt; 45초(§9.4).
     */
    public static final String PLAN_DURATION_SECONDS = "dawnline_plan_duration_seconds";

    /**
     * gauge · 라벨: {@link MetricTags#CAMP}.
     * 계획 결과 총비용(원). 돈은 정수 KRW 다(CLAUDE.md 불변규칙 9) — 게이지 값으로 올릴 때만
     * double 로 변환한다.
     */
    public static final String PLAN_COST_KRW = "dawnline_plan_cost_krw";

    /**
     * gauge · 라벨: {@link MetricTags#CAMP}.
     * 계획에서 배정되지 못한 주문 수(§6.5).
     */
    public static final String PLAN_UNASSIGNED = "dawnline_plan_unassigned";

    /**
     * counter · 라벨: {@link MetricTags#CAMP}.
     * 시간 예산 초과 등으로 FAST 열화 모드로 전환된 계획 횟수(§6.7).
     */
    public static final String PLAN_DEGRADED_TOTAL = "dawnline_plan_degraded_total";

    // --- tracking-service ----------------------------------------------------

    /**
     * gauge · 라벨: {@link MetricTags#CAMP}.
     * 약속 시간 내 배송 완료 비율(0.0~1.0). 알림 임계값 0.95 미만(§9.4).
     */
    public static final String DELIVERY_ON_TIME_RATIO = "dawnline_delivery_on_time_ratio";

    /**
     * counter · 라벨: {@link MetricTags#CAMP}.
     * 지연 위험(at-risk)으로 발행된 이벤트 수. 쿨다운이 걸리므로 라우트당 중복은 적다(§8.5).
     */
    public static final String AT_RISK_TOTAL = "dawnline_at_risk_total";

    /**
     * §9.1 표의 전체 메트릭 이름. 대시보드·알림 규칙 검증 테스트에서 이 목록을 기준으로 쓴다.
     *
     * <p>상수를 추가하면 이 목록에도 반드시 추가해야 한다 — {@code DawnlineMetricsTest} 가
     * 리플렉션으로 선언 필드와 이 목록이 일치하는지 검사하므로 빠뜨리면 테스트가 깨진다.
     */
    public static final List<String> ALL = List.of(
            ORDERS_PLACED_TOTAL,
            OUTBOX_LAG_SECONDS,
            OUTBOX_UNPUBLISHED,
            EVENT_PROCESSED_TOTAL,
            WAVE_ORDERS,
            PLAN_DURATION_SECONDS,
            PLAN_COST_KRW,
            PLAN_UNASSIGNED,
            PLAN_DEGRADED_TOTAL,
            DELIVERY_ON_TIME_RATIO,
            AT_RISK_TOTAL);
}
