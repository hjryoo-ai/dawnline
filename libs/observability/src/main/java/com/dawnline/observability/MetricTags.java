package com.dawnline.observability;

import java.util.List;

/**
 * Dawnline 메트릭 라벨(태그) 키와, 값이 열거형인 라벨의 값 상수 (DESIGN.md §9.1).
 *
 * <p>{@link DawnlineMetrics} 와 같은 이유로 상수화한다. 같은 개념을 {@code camp} 와
 * {@code campId} 로 각각 적으면 Grafana 쿼리에서 시계열이 둘로 갈라진다.
 *
 * <h2>카디널리티 규칙</h2>
 * <p>라벨 값은 <strong>유한하고 작아야</strong> 한다. 캠프 10개·티어 3개·전략 4개처럼
 * 미리 셀 수 있는 값만 라벨로 쓴다. 주문 ID·라우트 ID·웨이브 ID·좌표·주소는 라벨이 아니라
 * 로그 MDC({@link MdcKeys}) 또는 트레이스 속성으로 남긴다.
 *
 * <h2>Prometheus 라벨 이름</h2>
 * <p>{@link #EVENT_TYPE} 만 카멜케이스({@code eventType})다. 설계서 §9.1 표기를 그대로
 * 따랐고, Prometheus 라벨 이름 규칙 {@code [a-zA-Z_][a-zA-Z0-9_]*} 을 만족하므로
 * Micrometer 의 {@code sanitizeLabelName} 을 통과해도 변형되지 않는다.
 */
public final class MetricTags {

    private MetricTags() {
        throw new AssertionError("상수 홀더입니다. 인스턴스를 만들지 마세요.");
    }

    /** 서비스 티어. 값 예: {@code DAWN}, {@code SAME_DAY}, {@code STANDARD} (§2.2). */
    public static final String TIER = "tier";

    /** 배송 캠프 코드. 값 예: {@code CAMP-SEOUL-01} (§2.1). */
    public static final String CAMP = "camp";

    /** 메트릭을 낸 서비스 이름. 값은 {@code spring.application.name} 과 같다. */
    public static final String SERVICE = "service";

    /** Kafka 소비자 그룹 식별자. {@code processed_events.consumer} 와 같은 값을 쓴다(§4.4). */
    public static final String CONSUMER = "consumer";

    /** 이벤트 타입. 값 예: {@code order.placed} (§4.1 토픽·이벤트 이름). */
    public static final String EVENT_TYPE = "eventType";

    /** 처리 결과. 값은 아래 {@code OUTCOME_*} 넷 중 하나다. */
    public static final String OUTCOME = "outcome";

    /** 최적화 전략 이름. 값 예: {@code sweep-greedy-nn+ls} (§6.6). */
    public static final String STRATEGY = "strategy";

    /** 계획 모드. 값 예: {@code NORMAL}, {@code FAST} (§6.7 열화 모드). */
    public static final String MODE = "mode";

    /** {@link #OUTCOME} 값 — 정상 처리. */
    public static final String OUTCOME_OK = "ok";

    /** {@link #OUTCOME} 값 — 멱등 체크에 걸린 중복 전달. at-least-once 의 정상 동작이다. */
    public static final String OUTCOME_DUP = "dup";

    /** {@link #OUTCOME} 값 — 계약·상태 위반으로 거부. 재시도해도 성공하지 않는다. */
    public static final String OUTCOME_REJECTED = "rejected";

    /** {@link #OUTCOME} 값 — 재시도 소진 후 DLQ 로 보냄(§4.6). 알림 대상이다(§9.4). */
    public static final String OUTCOME_DLQ = "dlq";

    /** 라벨 키 전체. 테스트에서 중복·오타 검증에 쓴다. */
    public static final List<String> ALL_KEYS = List.of(
            TIER, CAMP, SERVICE, CONSUMER, EVENT_TYPE, OUTCOME, STRATEGY, MODE);

    /** {@link #OUTCOME} 이 가질 수 있는 값 전체 (§9.1). */
    public static final List<String> ALL_OUTCOMES = List.of(
            OUTCOME_OK, OUTCOME_DUP, OUTCOME_REJECTED, OUTCOME_DLQ);
}
