package com.dawnline.messaging;

/**
 * libs/messaging 이 등록하는 Micrometer 메트릭·태그 이름 (DESIGN.md §9.1, §4.6).
 *
 * <p><strong>중복 주의</strong>: 메트릭 이름 상수의 최종 소유자는 {@code libs/observability} 다
 * (IMPLEMENTATION_PLAN Phase 0 작업 4). 그 모듈이 병렬로 만들어지는 중이라 여기에 자체 상수를 두었다.
 * 통합 시 이 클래스는 {@code libs/observability} 의 상수를 재수출(re-export)하거나 삭제되어야 한다.
 *
 * <p>Micrometer 는 점 표기를 쓰고, Prometheus 레지스트리가 {@code _} 로 바꾸며 counter 에
 * {@code _total} 을 붙인다. 그래서 아래 이름은 설계서의 Prometheus 이름과 다음처럼 대응한다.
 *
 * <table>
 *   <caption>이름 대응</caption>
 *   <tr><th>여기</th><th>Prometheus (§9.1)</th></tr>
 *   <tr><td>dawnline.outbox.lag.seconds</td><td>dawnline_outbox_lag_seconds</td></tr>
 *   <tr><td>dawnline.outbox.unpublished</td><td>dawnline_outbox_unpublished</td></tr>
 *   <tr><td>dawnline.event.processed</td><td>dawnline_event_processed_total</td></tr>
 *   <tr><td>dawnline.event.rejected</td><td>dawnline_event_rejected_total</td></tr>
 *   <tr><td>dawnline.event.stale</td><td>dawnline_event_stale_total</td></tr>
 * </table>
 *
 * <p>{@code baseUnit} 을 쓰지 않고 이름에 {@code .seconds} 를 직접 넣은 이유는,
 * baseUnit 을 함께 주면 레지스트리가 접미사를 한 번 더 붙여
 * {@code dawnline_outbox_lag_seconds_seconds} 가 될 수 있기 때문이다.
 */
public final class MessagingMetrics {

    /** gauge — 가장 오래된 미발행 outbox 행의 경과 시간(초). 미발행이 없으면 0. */
    public static final String OUTBOX_LAG_SECONDS = "dawnline.outbox.lag.seconds";

    /** gauge — 미발행 outbox 행 수. */
    public static final String OUTBOX_UNPUBLISHED = "dawnline.outbox.unpublished";

    /** 격리된(미해결) outbox 행 수 (§9.1, §4.6). 0 이 아니면 알림 대상이다(§9.4, RB-05). */
    public static final String OUTBOX_FAILED = "dawnline.outbox.failed";

    /** counter — 이벤트 소비 결과. 태그: consumer, eventType, outcome. */
    public static final String EVENT_PROCESSED = "dawnline.event.processed";

    /** counter — 비즈니스 규칙 위반으로 무시한 이벤트 (§4.6). 태그: reason. */
    public static final String EVENT_REJECTED = "dawnline.event.rejected";

    /**
     * counter — 이미 지나온 지점으로의 전이라 무시한 이벤트 (§9.1, ADR-017). 태그: consumer, eventType.
     *
     * <p>{@link #EVENT_REJECTED} 와 합치지 않는 이유는 알림 때문이다. <strong>stale 은 늘 조금씩
     * 늘고 rejected 는 0이어야 한다.</strong> 한 카운터에 섞으면 어느 쪽에도 임계값을 걸 수 없다.
     *
     * <p>{@link #TAG_OUTCOME} 에 값을 하나 더하지 않은 이유: 그 축은 "멱등 소비의 결과"
     * (ok/dup/rejected/dlq)이고 stale 은 <em>상태 머신의 판정</em>이다. 이벤트는 정상적으로
     * 처리(커밋)됐고 다만 상태를 바꾸지 않았을 뿐이다.
     */
    public static final String EVENT_STALE = "dawnline.event.stale";

    /** 태그: 서비스 이름 (outbox 게이지). */
    public static final String TAG_SERVICE = "service";

    /** 태그: 소비자 이름. */
    public static final String TAG_CONSUMER = "consumer";

    /** 태그: 이벤트 타입. */
    public static final String TAG_EVENT_TYPE = "eventType";

    /** 태그: 소비 결과. {@link #OUTCOME_OK} / {@link #OUTCOME_DUP} / {@link #OUTCOME_REJECTED} / {@link #OUTCOME_DLQ} */
    public static final String TAG_OUTCOME = "outcome";

    /** 태그: 거부 사유. */
    public static final String TAG_REASON = "reason";

    /** outcome — 처음 소비했고 비즈니스 로직이 끝까지 실행됐다. */
    public static final String OUTCOME_OK = "ok";

    /** outcome — 이미 처리한 이벤트라 건너뛰었다 (§8.5). */
    public static final String OUTCOME_DUP = "dup";

    /** outcome — 비즈니스 규칙 위반이라 무시했다. DLQ 아님 (§4.6). */
    public static final String OUTCOME_REJECTED = "rejected";

    /** outcome — 재시도 소진 또는 즉시 실패로 DLQ 로 보냈다 (§4.6). */
    public static final String OUTCOME_DLQ = "dlq";

    /** 태그·메트릭 값이 비었을 때 쓰는 자리표시자. 라벨 카디널리티 폭발을 막는다. */
    public static final String UNKNOWN = "unknown";

    private MessagingMetrics() {
    }
}
