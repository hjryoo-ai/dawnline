package com.dawnline.tracking.adapter.out.messaging;

import com.dawnline.tracking.domain.Shipment;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code delivery.at-risk.v1} 페이로드
 * (계약: {@code contracts/events/delivery.at-risk.v1.schema.json}).
 *
 * <h2>사건이지 상태가 아니다</h2>
 * 편차가 계속 커지면 같은 라우트에서 다시 나가고(쿨다운이 그 주기다), <strong>위험이 사라지는
 * 경우는 알리지 않는다</strong> — 이미 시작된 재계획을 취소할 방법이 없고, 해소는 ops 의 읽기
 * 모델이 ETA 로 보여 줄 일이다([ADR-046](docs/adr/ADR-046-at-risk-is-an-event.md)).
 *
 * <h2>남은 stop 은 위험한 것만이 아니다</h2>
 * §6.8 의 부분 재계획이 다시 푸는 대상은 위험한 stop 하나가 아니라 <em>남은 구간</em>이다.
 * 위험 여부는 stop 마다 {@code atRisk} 로 함께 싣는다 — 여유(15분)는 tracking 의 정책이고,
 * 소비자가 다시 계산하면 두 곳이 갈라진다.
 *
 * @param routeId          라우트 id. 파티션 키와 같아야 한다 (§4.1)
 * @param campId           캠프 id. ops 는 이 이벤트만 보게 된다
 * @param detectedAt       판정 시각 (주입된 시계, 불변규칙 12)
 * @param deviationSeconds 이 판정을 부른 스캔의 편차(초). 이르면 음수다
 * @param remainingStops   아직 끝나지 않은 stop 들 (방문 순서)
 */
public record DeliveryAtRiskPayload(UUID routeId, UUID campId, String detectedAt,
        long deviationSeconds, List<RemainingStop> remainingStops) {

    /** {@code eventType}. */
    public static final String EVENT_TYPE = "delivery.at-risk";

    /** 페이로드 스키마 major. */
    public static final int SCHEMA_VERSION = 1;

    /** {@code outbox_events.aggregate_type}. 판정의 단위는 라우트다. */
    public static final String AGGREGATE_TYPE = "Route";

    /**
     * 남은 stop 하나.
     *
     * @param seq         방문 순번
     * @param orderIds    아직 끝나지 않은 주문들
     * @param etaAt       현재 ETA
     * @param promisedEnd 약속창의 끝
     * @param atRisk      이 stop 이 위험한가
     */
    public record RemainingStop(int seq, List<String> orderIds, String etaAt, String promisedEnd,
            boolean atRisk) {
    }

    /**
     * 남은 배송들에서 만든다 — <strong>주문 단위를 stop 단위로 되접는다.</strong>
     *
     * <p>{@code shipments} 는 주문 하나씩이지만 §6.5 1단계가 같은 지점의 주문을 한 stop 으로
     * 묶었고, 재계획도 stop 단위로 푼다. 되접지 않으면 소비자가 그것을 다시 해야 한다.
     * 한 stop 의 ETA·약속창은 그 stop 의 배송들이 공유한다(개정이 함께 준 값이다).
     *
     * @param routeId   라우트 id
     * @param campId    캠프 id
     * @param detectedAt 판정 시각
     * @param deviation  이 판정을 부른 스캔의 편차
     * @param remaining  아직 끝나지 않은 배송들 (순번 오름차순)
     * @param margin     at-risk 여유 (§5.4 기본 15분)
     * @return 페이로드
     */
    public static DeliveryAtRiskPayload of(UUID routeId, UUID campId, Instant detectedAt,
            Duration deviation, List<Shipment> remaining, Duration margin) {

        Map<Integer, List<Shipment>> byStop = new LinkedHashMap<>();
        for (Shipment shipment : remaining) {
            byStop.computeIfAbsent(shipment.stopSeq(), seq -> new ArrayList<>()).add(shipment);
        }
        List<RemainingStop> stops = new ArrayList<>(byStop.size());
        for (Map.Entry<Integer, List<Shipment>> entry : byStop.entrySet()) {
            Shipment first = entry.getValue().getFirst();
            stops.add(new RemainingStop(entry.getKey(),
                    entry.getValue().stream().map(s -> s.orderId().toString()).toList(),
                    first.etaAt().toString(), first.promisedEnd().toString(),
                    entry.getValue().stream().anyMatch(s -> s.isAtRisk(margin))));
        }
        return new DeliveryAtRiskPayload(routeId, campId, detectedAt.toString(),
                deviation.toSeconds(), stops);
    }
}
