package com.dawnline.messaging;

/**
 * libs/messaging 이 쓰는 라벨 <strong>키와 값</strong> (DESIGN.md §9.1, §4.6).
 *
 * <p>메트릭 <strong>이름</strong>은 여기 없다 — 카탈로그 {@code com.dawnline.observability.DawnlineMetrics} 가 §9.1 의
 * 행마다 항목 하나를 들고, 등록은 {@code DawnlineMeters} 가 한다(ADR-060). 이 클래스는 처음부터 「이름의 최종 소유자는
 * libs/observability 다 — 통합 시 재수출하거나 삭제한다」고 적고 있었고, 그 통합이 2026-09-25 에 이름 쪽에서 끝났다.
 * 라벨 키 · 값은 이름이 아니라 소비의 어휘라 남는다. 키가 카탈로그와 다르면 등록이 실패한다.
 */
public final class MessagingMetrics {

    /** 태그: 보존 정리의 표 이름 — §7.1 보존 표의 첫 열. */
    public static final String TAG_TABLE = "table";

    /** 태그: 서비스 이름 (outbox 게이지). */
    public static final String TAG_SERVICE = "service";

    /** 태그: 소비자 이름. */
    public static final String TAG_CONSUMER = "consumer";

    /** 태그: 이벤트 타입. */
    public static final String TAG_EVENT_TYPE = "eventType";

    /**
     * 태그: 소비 결과. {@link #OUTCOME_OK} / {@link #OUTCOME_DUP} / {@link #OUTCOME_REJECTED} / {@link #OUTCOME_DLQ} /
     * {@link #OUTCOME_REPLAY_NOT_TARGET}
     */
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

    /**
     * outcome — 다른 그룹을 지목한 DLQ 재처리라 리스너를 부르지 않고 건너뛰었다 (§4.6, ADR-053).
     *
     * <p>{@link #OUTCOME_DUP} 과 합치지 않는다. {@code dup} 은 「이미 처리했다」이고 이것은 「내 일이 아니다」다 —
     * 합치면 {@code dup} 이 는 것이 재전달인지 재처리인지 가릴 수 없다. 그리고 이 결과만 {@code processed_events}
     * 에 흔적이 없다. 이 카운터가 유일한 흔적이다.
     */
    public static final String OUTCOME_REPLAY_NOT_TARGET = "replay_not_target";

    /** 태그·메트릭 값이 비었을 때 쓰는 자리표시자. 라벨 카디널리티 폭발을 막는다. */
    public static final String UNKNOWN = "unknown";

    private MessagingMetrics() {
    }
}
