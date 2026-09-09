package com.dawnline.dispatch.adapter.in.messaging;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.ValidationException;
import com.dawnline.dispatch.application.port.in.PlannedOrderSnapshot;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * {@code fulfillment.planned.v1} 페이로드 → {@link PlannedOrderSnapshot}.
 *
 * <p>페이로드에는 주소 문자열·고객 id·품목이 함께 오지만 <strong>계획이 쓰는 것만</strong>
 * 꺼낸다. 담지 않으면 로그에 샐 수도 없다(CLAUDE.md — 전체 주소·고객 식별 정보 로그 금지).
 *
 * <h2>계약에 없는 값 하나 — {@code serviceSeconds}</h2>
 * 하차·전달 시간은 <em>배송 운영</em>의 값이지 주문의 속성이 아니다. 기본값을 여기서 준다.
 * 캠프·차종별로 달라지면 참조 데이터에서 온다.
 *
 * <h2>우선도는 여기서 만들지 않는다</h2>
 * 계약에는 여전히 {@code priority} 가 없고, <strong>그것이 맞다</strong>([ADR-028]) — 클라이언트
 * 값은 신뢰할 수 없고 티어에서 파생하면 한 웨이브 안에서 상수가 된다. 대신 계약에 이미 있는
 * <strong>사실</strong> {@code promiseRevised} 를 그대로 나르고, 점수표를 적용하는 것은
 * {@code LoadCandidateService} 다. 어댑터는 계약을 옮기는 곳이지 정책을 아는 곳이 아니다.
 */
final class FulfillmentPlannedPayload {

    /** {@code outcome} 이 이 값일 때만 계획 후보다. */
    static final String PLANNED = "PLANNED";

    /** 기본 하차·전달 시간(초). 계약에 없어 여기서 준다. */
    static final int DEFAULT_SERVICE_SECONDS = 90;

    private FulfillmentPlannedPayload() {
    }

    /** 이 이벤트가 계획 후보인가. {@code UNSERVICEABLE} 은 이미 종결된 주문이다(§5.2 6단계). */
    static boolean isCandidate(JsonNode payload) {
        return PLANNED.equals(text(payload, "outcome"));
    }

    /**
     * 스냅샷으로 옮긴다.
     *
     * @param payload {@code fulfillment.planned} 페이로드
     */
    static PlannedOrderSnapshot toSnapshot(JsonNode payload) {
        JsonNode address = required(payload, "address");
        JsonNode parcel = required(payload, "parcel");
        JsonNode window = required(payload, "promisedWindow");

        return new PlannedOrderSnapshot(
                uuid(payload, "orderId"),
                uuid(payload, "waveId"),
                uuid(payload, "campId"),
                payload.has("zoneId") ? uuid(payload, "zoneId") : null,
                GeoPoint.of(address.get("lat").doubleValue(), address.get("lng").doubleValue()),
                parcel.get("weightG").intValue(),
                parcel.get("volumeCm3").intValue(),
                flag(parcel, "requiresCold"),
                flag(parcel, "hazmat"),
                new TimeWindow(Instant.parse(text(window, "start")), Instant.parse(text(window, "end"))),
                DEFAULT_SERVICE_SECONDS,
                flag(payload, "promiseRevised"));
    }

    /** 없으면 거짓. 스키마가 기본값을 주지 않는 불리언 필드에 쓴다. */
    private static boolean flag(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.booleanValue();
    }

    private static JsonNode required(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            throw new ValidationException("fulfillment.planned 에 %s 가 없습니다".formatted(field),
                    Map.of("field", field));
        }
        return node;
    }

    private static UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(text(payload, field));
    }

    private static String text(JsonNode payload, String field) {
        return required(payload, field).asString();
    }
}
