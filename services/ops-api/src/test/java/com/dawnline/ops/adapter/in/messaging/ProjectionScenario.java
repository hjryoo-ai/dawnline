package com.dawnline.ops.adapter.in.messaging;

import com.dawnline.common.Ids;
import com.dawnline.messaging.Topics;
import com.dawnline.messaging.contract.EventContracts;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 순서를 뒤섞을 사실 집합 — 웨이브 하나의 하루 (ADR-051 결정 6).
 *
 * <p>모든 이벤트는 <strong>계약 예시에서</strong> 만들고 만든 뒤 스키마로 검증한다 — 이 집합이
 * 보는 모양은 상상한 것이 아니라 계약에 적힌 것이다. 식별자와 시각만 이 클래스가 정한다.
 *
 * <h2>무엇이 들어 있나</h2>
 * 웨이브 W 에 주문 다섯, 라우트 둘. 순서 역전이 결과를 바꿀 수 있는 자리를 일부러 다 넣었다:
 * <ul>
 *   <li>O2 — 약속이 개정되고(원 약속 ≠ 개정 약속), 재계획이 R1 에서 R2 로 옮기고, 두 라우트의
 *       at-risk 가 둘 다 이 주문의 ETA 를 말하고, 끝내 배송에 실패한다.</li>
 *   <li>O4 — 계획 뒤에 취소됐는데 배송됐다. 「취소됐는데 배송됨」의 예외 목록 행이다(§5.5).</li>
 *   <li>O5 — 배차 불가. 캠프·웨이브 칸이 끝까지 비어 있어야 한다.</li>
 *   <li>W — 계획이 한 번 실패했다가 성공한다.</li>
 *   <li>R1·R2 — 개정 두 번, 출발, at-risk.</li>
 * </ul>
 *
 * <p>시각은 한 기준점에서 파생한 리터럴이다. 프로젝션은 이 값들을 벽시계와 비교하지 않고 서로만
 * 견준다({@code planned_as_of}·{@code eta_as_of}) — CLAUDE.md 의 「시계와 비교되는 경로」가 아니다.
 */
public final class ProjectionScenario {

    /** 이벤트 하나 — Kafka 레코드 그대로. {@code eventId} 는 봉투의 것이다(IT 가 자기 소비 기록을 지운다). */
    public record Event(String topic, String key, String value, UUID eventId, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * 이 사실 집합이 쓰지 않는 토픽과 그 이유 — <strong>빼는 방식</strong>(§13 규칙 2, ADR-051 결정 6 의 3).
     * 순서 검사는 리스너가 구독하는 토픽 전부에서 시작하고, 뺄 때는 여기에 이유를 적는다. 비어 있는
     * 것이 정상이다 — 토픽이 붙었는데 여기에도 시나리오에도 없으면 두 순서 검사가 빨갛다.
     */
    public static final Map<String, String> EXCLUDED_TOPICS = Map.of();

    private static final Instant BASE = Instant.parse("2026-08-29T12:00:00Z");

    public final UUID campId = Ids.newId();
    public final UUID waveId = Ids.newId();
    public final UUID failedPlanId = Ids.newId();
    public final UUID planId = Ids.newId();
    public final UUID r1 = Ids.newId();
    public final UUID r2 = Ids.newId();
    public final UUID o1 = Ids.newId();
    public final UUID o2 = Ids.newId();
    public final UUID o3 = Ids.newId();
    public final UUID o4 = Ids.newId();
    public final UUID o5 = Ids.newId();

    /** 원 약속의 끝 — 배송 시각들은 이보다 앞이라 원 약속 기준 정시다. */
    public final Instant promisedEnd = at(600);
    /** O2 의 개정 약속 — 원 약속보다 늦다. */
    public final Instant revisedEnd = at(900);
    public final Instant r1Departed = at(185);
    public final Instant o1Delivered = at(200);
    public final Instant o3Delivered = at(260);
    public final Instant o4Delivered = at(270);
    public final Instant o2EtaFirst = at(300);
    public final Instant o2EtaSecond = at(320);
    public final Instant o2ArrivalOnR2 = at(290);
    /** 재계획이 R1·R2 를 개정한 트랜잭션의 발행 시각 — 봉투의 {@code occurredAt}. */
    public final Instant replanPublished = at(215);
    /** R2 의 at-risk 판정 시각 — 페이로드의 {@code detectedAt}. */
    public final Instant r2AtRiskDetected = at(240);

    private final EventContracts contracts;
    private final List<Event> causal = new ArrayList<>();
    private int sequence;

    /**
     * @param contracts 계약 디렉터리
     */
    public ProjectionScenario(EventContracts contracts) {
        this.contracts = contracts;
        build();
    }

    /** 이 시나리오가 만드는 행의 키 전부 — IT 가 자기 행만 읽고 지운다. */
    public List<UUID> keys() {
        return List.of(campId, waveId, r1, r2, o1, o2, o3, o4, o5);
    }

    /** 인과 순서 — 기준 행을 만드는 순서다(ADR-051 결정 6 의 1). */
    public List<Event> causalOrder() {
        return Collections.unmodifiableList(causal);
    }

    private static Instant at(int minutes) {
        return BASE.plus(Duration.ofMinutes(minutes));
    }

    private void build() {
        for (UUID order : List.of(o1, o2, o3, o4, o5)) {
            orderPlaced(order);
        }
        fulfillmentPlanned(o1, promisedEnd, false);
        fulfillmentPlanned(o2, revisedEnd, true);
        fulfillmentPlanned(o3, promisedEnd, false);
        fulfillmentPlanned(o4, promisedEnd, false);
        unserviceable(o5);
        orderCancelled(o4);
        waveClosed();
        planFailed();

        // 계획이 PUBLISHED 에 닿는 한 트랜잭션 — 같은 발행 시각이다(ADR-024).
        Instant published = at(150);
        planCompleted();
        routeAssigned(r1, 1, published, List.of(List.of(o1), List.of(o2)), List.of(at(190), at(230)));
        routeAssigned(r2, 1, published, List.of(List.of(o3), List.of(o4)), List.of(at(250), at(265)));
        for (UUID order : List.of(o1, o2, o3, o4)) {
            orderDispatched(order, order.equals(o1) || order.equals(o2) ? r1 : r2);
        }

        routeDeparted(r1, r1Departed);
        routeDeparted(r2, at(186));
        deliveryStatus(r1, "COMPLETED", o1Delivered, o1);
        atRisk(r1, at(210), List.of(new Eta(List.of(o2), o2EtaFirst)));

        // 재계획 — O2 를 R1 에서 R2 로 옮긴다. 두 라우트가 같은 트랜잭션에서 개정된다(§6.8 3단계의 3).
        Instant replanned = replanPublished;
        routeAssigned(r1, 2, replanned, List.of(List.of(o1)), List.of(at(190)));
        routeAssigned(r2, 2, replanned, List.of(List.of(o3), List.of(o4), List.of(o2)),
                List.of(at(250), at(265), o2ArrivalOnR2));
        atRisk(r2, r2AtRiskDetected, List.of(new Eta(List.of(o3), at(258)), new Eta(List.of(o4), at(268)),
                new Eta(List.of(o2), o2EtaSecond)));

        deliveryStatus(r2, "ARRIVED", at(255), o3);
        deliveryStatus(r2, "COMPLETED", o3Delivered, o3);
        deliveryStatus(r2, "COMPLETED", o4Delivered, o4);
        deliveryStatus(r2, "FAILED", at(330), o2);
    }

    // --- 이벤트 만들기 -----------------------------------------------------------

    private record Eta(List<UUID> orderIds, Instant etaAt) {
    }

    private ObjectNode example(String fileName) {
        return (ObjectNode) contracts.readTree(contracts.contractsDirectory().resolve(Path.of("examples", fileName)));
    }

    private void add(ObjectNode envelope, UUID key, String label) {
        add(envelope, key, label, BASE.plusSeconds(sequence + 1L));
    }

    /**
     * @param occurredAt 봉투의 발행 시각. 인과 순서대로 증가하는 값이 기본이고, 이 값을 <em>읽는</em>
     *                   route.assigned(계획 칸의 견줌)만 호출자가 따로 정한다
     */
    private void add(ObjectNode envelope, UUID key, String label, Instant occurredAt) {
        sequence++;
        UUID eventId = Ids.newId();
        envelope.put("eventId", eventId.toString());
        envelope.put("partitionKey", key.toString());
        envelope.put("occurredAt", occurredAt.toString());
        contracts.validateRecord(envelope);
        String eventType = envelope.get("eventType").asString();
        causal.add(new Event(Topics.forEvent(eventType, 1), key.toString(),
                contracts.json().write(envelope), eventId, "#" + sequence + " " + label));
    }

    private void orderPlaced(UUID order) {
        ObjectNode e = example("order.placed.v1.example.json");
        ObjectNode p = (ObjectNode) e.get("payload");
        p.put("orderId", order.toString());
        ((ObjectNode) p.get("promisedWindow")).put("start", at(300).toString()).put("end", promisedEnd.toString());
        add(e, order, "order.placed " + name(order));
    }

    private void fulfillmentPlanned(UUID order, Instant end, boolean revised) {
        ObjectNode e = example(revised ? "fulfillment.planned.v1.revised.example.json"
                : "fulfillment.planned.v1.example.json");
        ObjectNode p = (ObjectNode) e.get("payload");
        p.put("orderId", order.toString());
        p.put("campId", campId.toString());
        p.put("waveId", waveId.toString());
        p.put("serviceTier", "SAME_DAY");
        p.put("waveCutoffAt", at(120).toString());
        ((ObjectNode) p.get("promisedWindow")).put("start", end.minus(Duration.ofHours(5)).toString())
                .put("end", end.toString());
        add(e, order, "fulfillment.planned " + name(order));
    }

    private void unserviceable(UUID order) {
        ObjectNode e = example("fulfillment.planned.v1.unserviceable.example.json");
        ((ObjectNode) e.get("payload")).put("orderId", order.toString());
        add(e, order, "fulfillment.planned(UNSERVICEABLE) " + name(order));
    }

    private void orderCancelled(UUID order) {
        ObjectNode e = example("order.cancelled.v1.example.json");
        ((ObjectNode) e.get("payload")).put("orderId", order.toString());
        add(e, order, "order.cancelled " + name(order));
    }

    private void waveClosed() {
        ObjectNode e = example("wave.closed.v1.example.json");
        ObjectNode p = (ObjectNode) e.get("payload");
        p.put("waveId", waveId.toString()).put("campId", campId.toString())
                .put("serviceTier", "SAME_DAY").put("cutoffAt", at(120).toString()).put("orderCount", 4);
        add(e, campId, "wave.closed");
    }

    private void planFailed() {
        ObjectNode e = example("plan.failed.v1.example.json");
        ((ObjectNode) e.get("payload")).put("planId", failedPlanId.toString()).put("waveId", waveId.toString())
                .put("campId", campId.toString());
        add(e, waveId, "plan.failed");
    }

    private void planCompleted() {
        ObjectNode e = example("plan.completed.v1.example.json");
        ((ObjectNode) e.get("payload")).put("planId", planId.toString()).put("waveId", waveId.toString())
                .put("campId", campId.toString()).put("routeCount", 2).put("assignedCount", 4)
                .put("unassignedCount", 0).put("totalCostKrw", 43000L).put("planDurationMs", 1200);
        add(e, waveId, "plan.completed");
    }

    private void routeAssigned(UUID route, int revision, Instant published, List<List<UUID>> stops,
            List<Instant> arrivals) {
        ObjectNode e = example("route.assigned.v1.example.json");
        ObjectNode p = (ObjectNode) e.get("payload");
        p.put("routeId", route.toString()).put("planId", planId.toString()).put("waveId", waveId.toString())
                .put("campId", campId.toString()).put("revision", revision);
        ((ObjectNode) p.get("summary")).put("stopCount", stops.size())
                .put("distanceM", 1000 * revision + stops.size())
                .put("costKrw", 20000 + revision)
                .put("plannedDeparture", at(180).toString());
        ArrayNode stopNodes = (ArrayNode) p.get("stops");
        JsonNode template = stopNodes.get(0);
        stopNodes.removeAll();
        for (int i = 0; i < stops.size(); i++) {
            ObjectNode stop = (ObjectNode) template.deepCopy();
            stop.put("seq", i + 1);
            ArrayNode ids = stop.putArray("orderIds");
            stops.get(i).forEach(id -> ids.add(id.toString()));
            stop.put("plannedArrival", arrivals.get(i).toString());
            ((ObjectNode) stop.get("promisedWindow")).put("start", at(300).toString())
                    .put("end", promisedEnd.toString());
            stopNodes.add(stop);
        }
        add(e, route, "route.assigned " + name(route) + " rev" + revision, published);
    }

    private void orderDispatched(UUID order, UUID route) {
        ObjectNode e = example("order.dispatched.v1.example.json");
        ObjectNode p = (ObjectNode) e.get("payload");
        p.put("orderId", order.toString()).put("routeId", route.toString());
        if (p.has("planId")) {
            p.put("planId", planId.toString());
        }
        if (p.has("waveId")) {
            p.put("waveId", waveId.toString());
        }
        add(e, order, "order.dispatched " + name(order));
    }

    private void routeDeparted(UUID route, Instant departedAt) {
        ObjectNode e = example("delivery.route-departed.v1.example.json");
        ((ObjectNode) e.get("payload")).put("routeId", route.toString()).put("campId", campId.toString())
                .put("departedAt", departedAt.toString()).put("plannedDeparture", at(180).toString());
        add(e, route, "delivery.route-departed " + name(route));
    }

    private void deliveryStatus(UUID route, String status, Instant occurredAt, UUID... orders) {
        ObjectNode e = example(status.equals("FAILED") ? "delivery.status.v1.failed.example.json"
                : "delivery.status.v1.example.json");
        ObjectNode p = (ObjectNode) e.get("payload");
        p.put("routeId", route.toString()).put("status", status).put("occurredAt", occurredAt.toString());
        ArrayNode ids = p.putArray("orderIds");
        for (UUID order : orders) {
            ids.add(order.toString());
        }
        add(e, route, "delivery.status " + status + " " + name(route) + " " + names(orders));
    }

    private void atRisk(UUID route, Instant detectedAt, List<Eta> remaining) {
        ObjectNode e = example("delivery.at-risk.v1.example.json");
        ObjectNode p = (ObjectNode) e.get("payload");
        p.put("routeId", route.toString()).put("campId", campId.toString()).put("detectedAt", detectedAt.toString());
        ArrayNode stops = (ArrayNode) p.get("remainingStops");
        JsonNode template = stops.get(0);
        stops.removeAll();
        for (int i = 0; i < remaining.size(); i++) {
            ObjectNode stop = (ObjectNode) template.deepCopy();
            stop.put("seq", i + 1);
            ArrayNode ids = stop.putArray("orderIds");
            remaining.get(i).orderIds().forEach(id -> ids.add(id.toString()));
            stop.put("etaAt", remaining.get(i).etaAt().toString());
            stops.add(stop);
        }
        add(e, route, "delivery.at-risk " + name(route));
    }

    /** 실패 메시지가 UUID 대신 이름을 말하게 한다. */
    public String name(UUID id) {
        if (id.equals(o1)) return "O1";
        if (id.equals(o2)) return "O2";
        if (id.equals(o3)) return "O3";
        if (id.equals(o4)) return "O4";
        if (id.equals(o5)) return "O5";
        if (id.equals(r1)) return "R1";
        if (id.equals(r2)) return "R2";
        if (id.equals(waveId)) return "W";
        return id.toString();
    }

    private String names(UUID... ids) {
        List<String> names = new ArrayList<>();
        for (UUID id : ids) {
            names.add(name(id));
        }
        return names.toString();
    }
}
