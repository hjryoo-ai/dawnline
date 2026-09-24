package com.dawnline.tracking.adapter.out.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code delivery.route-departed.v1} 페이로드
 * (계약: {@code contracts/events/delivery.route-departed.v1.schema.json}, ADR-050).
 *
 * <p><strong>소비자가 먼저 정의한</strong> 계약이다 — ops 가 묶음 B 의 프로젝션 커밋에서 썼고
 * tracking 은 그것을 따른다. 다섯 칸 전부 새 컬럼 없이 나온다: 캠프·개정·계획 출발은
 * {@code route_revisions} 한 행에, 출발 시각은 스캔에 있다. stop 수는 싣지 않는다 — 진실은
 * dispatch 의 계획이고 ops 는 {@code route.assigned} 에서 받는다(ADR-050 재검토 지점 3).
 *
 * @param routeId          라우트 id. 파티션 키와 같아야 한다 (§4.1)
 * @param campId           캠프 id
 * @param revision         출발 시점에 적용해 둔 개정
 * @param plannedDeparture 그 개정의 계획 출발 (ISO-8601)
 * @param departedAt       기사가 {@code DEPARTED_CAMP} 를 찍은 시각 (ISO-8601)
 */
public record RouteDepartedPayload(UUID routeId, UUID campId, int revision, String plannedDeparture,
        String departedAt) {

    /** {@code eventType}. */
    public static final String EVENT_TYPE = "delivery.route-departed";

    /** 페이로드 스키마 major. */
    public static final int SCHEMA_VERSION = 1;

    /** {@code outbox_events.aggregate_type}. 출발은 라우트의 사건이다. */
    public static final String AGGREGATE_TYPE = "Route";

    /**
     * @param routeId          라우트
     * @param campId           캠프
     * @param revision         개정 (1 이상)
     * @param plannedDeparture 계획 출발
     * @param departedAt       출발
     * @return 페이로드
     */
    public static RouteDepartedPayload of(UUID routeId, UUID campId, int revision, Instant plannedDeparture,
            Instant departedAt) {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(campId, "campId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision 은 1 이상이어야 합니다: " + revision);
        }
        return new RouteDepartedPayload(routeId, campId, revision,
                Objects.requireNonNull(plannedDeparture, "plannedDeparture").toString(),
                Objects.requireNonNull(departedAt, "departedAt").toString());
    }
}
