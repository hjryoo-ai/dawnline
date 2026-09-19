package com.dawnline.tracking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.AssignedStop;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.Outcome;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.RouteAssignment;
import com.dawnline.tracking.application.port.out.RouteRevisions;
import com.dawnline.tracking.application.port.out.ShipmentRepository;
import com.dawnline.tracking.domain.Shipment;
import com.dawnline.tracking.domain.ShipmentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@code route.assigned} 반영 규칙 (DESIGN.md §5.4, ADR-045).
 *
 * <p>시각 픽스처는 전부 <strong>주입된 시계에서 파생</strong>한다(CLAUDE.md). 계획 도착 시각은
 * 이 단계에서 벽시계와 비교되지 않지만, 「어디서 왔는가」가 한 곳이면 5-1b 의 at-risk 가 붙을 때
 * 고칠 자리도 한 곳이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("route.assigned 반영")
class ApplyRouteAssignmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T21:10:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final Instant ARRIVAL = NOW.plus(Duration.ofMinutes(30));
    private static final Instant PROMISED_END = NOW.plus(Duration.ofHours(3));

    private static final UUID ROUTE = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID OTHER_ORDER = UUID.randomUUID();

    private InMemoryShipments shipments;
    private RecordingRevisions revisions;
    private ApplyRouteAssignmentService service;

    @BeforeEach
    void setUp() {
        shipments = new InMemoryShipments();
        revisions = new RecordingRevisions();
        service = new ApplyRouteAssignmentService(shipments, revisions, CLOCK);
    }

    // --- 개정 비교 -----------------------------------------------------------

    @Test
    void 지난_개정이면_아무것도_읽지_않는다() {
        revisions.grant = false;

        Outcome outcome = service.apply(assignment(3, stop(1, List.of(ORDER), Set.of())));

        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.STALE);
        assertThat(shipments.findAllCalls)
                .as("선점에 실패하면 배송을 읽지도 않는다 — 읽어 봐야 쓸 수 없다")
                .isEmpty();
        assertThat(shipments.inserted).isEmpty();
        assertThat(shipments.updated).isEmpty();
    }

    @Test
    void 선점은_라우트와_개정_번호와_주입된_시각으로_한다() {
        service.apply(assignment(4, stop(1, List.of(ORDER), Set.of())));

        assertThat(revisions.calls).singleElement()
                .isEqualTo(new Claim(ROUTE, 4, NOW));
    }

    // --- 새 배송 -------------------------------------------------------------

    @Test
    void 새_주문은_SCHEDULED_로_만들어지고_ETA_는_계획_도착이다() {
        Outcome outcome = service.apply(assignment(1, stop(2, List.of(ORDER), Set.of())));

        assertThat(outcome.created()).isEqualTo(1);
        assertThat(outcome.revised()).isZero();
        Shipment created = shipments.stored.get(ORDER);
        assertThat(created.status()).isEqualTo(ShipmentStatus.SCHEDULED);
        assertThat(created.routeId()).isEqualTo(ROUTE);
        assertThat(created.stopSeq()).isEqualTo(2);
        assertThat(created.plannedArrival()).isEqualTo(ARRIVAL);
        assertThat(created.etaAt()).as("§5.4 — eta_at 의 초기값은 planned_arrival 이다")
                .isEqualTo(ARRIVAL);
        assertThat(created.promisedEnd()).isEqualTo(PROMISED_END);
    }

    @Test
    void 한_stop_의_여러_주문이_각각_배송이_된다() {
        // StopMerger 가 같은 지점의 주문을 묶는다(§6.5 1단계). shipments 의 PK 는 order_id 다.
        Outcome outcome = service.apply(
                assignment(1, stop(1, List.of(ORDER, OTHER_ORDER), Set.of())));

        assertThat(outcome.created()).isEqualTo(2);
        assertThat(shipments.stored).containsOnlyKeys(ORDER, OTHER_ORDER);
        assertThat(shipments.stored.get(OTHER_ORDER).stopSeq()).isEqualTo(1);
    }

    @Test
    void 처음_보는_주문이_이미_취소돼_있으면_취소로_만든다() {
        // 개정 2가 개정 1보다 먼저 처리되는 경우다. 만들지 않으면 그 주문은 어떤 개정으로도
        // 만들어지지 않는다 — 개정 1은 뒤에 와도 지난 개정이라 버려진다.
        Outcome outcome = service.apply(assignment(2, stop(1, List.of(ORDER), Set.of(ORDER))));

        assertThat(outcome.created()).isEqualTo(1);
        assertThat(outcome.cancelled()).isEqualTo(1);
        assertThat(shipments.stored.get(ORDER).status()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    // --- 기존 배송 -----------------------------------------------------------

    @Test
    void 이미_있는_배송은_계획이_갱신된다() {
        UUID newRoute = UUID.randomUUID();
        shipments.put(Shipment.scheduled(ORDER, ROUTE, 5, ARRIVAL, PROMISED_END));

        Outcome outcome = service.apply(new RouteAssignment(newRoute, 2,
                List.of(stop(9, List.of(ORDER), Set.of(), ARRIVAL.plus(Duration.ofMinutes(20)),
                        PROMISED_END.plus(Duration.ofMinutes(20))))));

        assertThat(outcome.created()).isZero();
        assertThat(outcome.revised()).isEqualTo(1);
        Shipment revised = shipments.stored.get(ORDER);
        assertThat(revised.routeId()).as("§6.8 relocate — 개정은 라우트를 옮길 수 있다")
                .isEqualTo(newRoute);
        assertThat(revised.stopSeq()).isEqualTo(9);
        assertThat(revised.plannedArrival()).isEqualTo(ARRIVAL.plus(Duration.ofMinutes(20)));
        assertThat(shipments.updated).containsExactly(ORDER);
    }

    @Test
    void 이번_개정에서_취소된_주문은_CANCELLED_로_옮긴다() {
        shipments.put(Shipment.scheduled(ORDER, ROUTE, 1, ARRIVAL, PROMISED_END));

        Outcome outcome = service.apply(assignment(2, stop(1, List.of(ORDER), Set.of(ORDER))));

        assertThat(outcome.cancelled()).isEqualTo(1);
        assertThat(shipments.stored.get(ORDER).status()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    void 같은_취소가_다시_실려_와도_두_번_세지_않는다() {
        // 취소된 stop 은 페이로드에서 지우지 않으므로(ADR-026) 개정마다 계속 실려 온다.
        shipments.put(restored(ShipmentStatus.CANCELLED));

        Outcome outcome = service.apply(assignment(2, stop(1, List.of(ORDER), Set.of(ORDER))));

        assertThat(outcome.cancelled()).isZero();
        assertThat(outcome.keptTerminal())
                .as("CANCELLED 는 종결이다 — 갱신할 것이 없다")
                .isEqualTo(1);
    }

    // --- 종결 상태 -----------------------------------------------------------

    @Test
    void 종결된_배송은_개정이_와도_그대로_두고_센다() {
        shipments.put(restored(ShipmentStatus.COMPLETED));

        Outcome outcome = service.apply(assignment(3, stop(7, List.of(ORDER), Set.of(),
                ARRIVAL.plus(Duration.ofHours(1)), PROMISED_END.plus(Duration.ofHours(1)))));

        assertThat(outcome.keptTerminal()).isEqualTo(1);
        assertThat(outcome.revised()).isZero();
        assertThat(shipments.updated).as("쓰지도 않는다 — 낙관적 락 버전을 헛되이 올린다").isEmpty();
        Shipment kept = shipments.stored.get(ORDER);
        assertThat(kept.stopSeq()).isEqualTo(1);
        assertThat(kept.plannedArrival()).isEqualTo(ARRIVAL);
    }

    @Test
    void 종결된_배송에는_취소도_얹지_않는다() {
        // 배송이 끝난 주문에 취소가 온 경우다. 세는 곳은 dispatch 의
        // dawnline_cancel_too_late_total 이다 (§6.10 넷째 분기) — 여기서 상태를 뒤집지 않는다.
        shipments.put(restored(ShipmentStatus.COMPLETED));

        Outcome outcome = service.apply(assignment(2, stop(1, List.of(ORDER), Set.of(ORDER))));

        assertThat(shipments.stored.get(ORDER).status()).isEqualTo(ShipmentStatus.COMPLETED);
        assertThat(outcome.cancelled()).isZero();
        assertThat(outcome.keptTerminal()).isEqualTo(1);
    }

    // --- 부재는 값이 아니다 ---------------------------------------------------

    @Test
    void 이_개정에_없는_주문은_읽지도_쓰지도_않는다() {
        // 라우트 간 이동의 경합 방어선이다: A 의 개정에서 빠진 주문은 B 로 옮겨간 것일 수 있고,
        // A 가 "없으니 정리" 를 하면 그 이동이 취소된다 (ADR-026 — 부재는 값이 아니다).
        shipments.put(Shipment.scheduled(OTHER_ORDER, ROUTE, 2, ARRIVAL, PROMISED_END));

        service.apply(assignment(2, stop(1, List.of(ORDER), Set.of())));

        assertThat(shipments.findAllCalls).singleElement()
                .as("페이로드에 이름이 적힌 주문만 읽는다")
                .isEqualTo(Set.of(ORDER));
        assertThat(shipments.updated).doesNotContain(OTHER_ORDER);
        assertThat(shipments.stored.get(OTHER_ORDER).stopSeq())
                .as("다른 개정이 준 자리가 그대로 남아 있다")
                .isEqualTo(2);
    }

    // --- 픽스처 --------------------------------------------------------------

    private static RouteAssignment assignment(int revision, AssignedStop... stops) {
        return new RouteAssignment(ROUTE, revision, List.of(stops));
    }

    private static AssignedStop stop(int seq, List<UUID> orderIds, Set<UUID> cancelled) {
        return stop(seq, orderIds, cancelled, ARRIVAL, PROMISED_END);
    }

    private static AssignedStop stop(int seq, List<UUID> orderIds, Set<UUID> cancelled,
            Instant arrival, Instant promisedEnd) {
        return new AssignedStop(seq, orderIds, cancelled, arrival, promisedEnd);
    }

    private static Shipment restored(ShipmentStatus status) {
        Instant deliveredAt = status == ShipmentStatus.COMPLETED ? NOW : null;
        return Shipment.restore(ORDER, ROUTE, 1, status, ARRIVAL, ARRIVAL, PROMISED_END,
                deliveredAt, 3L);
    }

    /** 선점 호출 하나. */
    private record Claim(UUID routeId, int revision, Instant appliedAt) {
    }

    private static final class RecordingRevisions implements RouteRevisions {

        private final List<Claim> calls = new ArrayList<>();
        private boolean grant = true;

        @Override
        public boolean claim(UUID routeId, int revision, Instant appliedAt) {
            calls.add(new Claim(routeId, revision, appliedAt));
            return grant;
        }
    }

    private static final class InMemoryShipments implements ShipmentRepository {

        private final Map<UUID, Shipment> stored = new LinkedHashMap<>();
        private final List<Set<UUID>> findAllCalls = new ArrayList<>();
        private final List<UUID> inserted = new ArrayList<>();
        private final List<UUID> updated = new ArrayList<>();

        void put(Shipment shipment) {
            stored.put(shipment.orderId(), shipment);
        }

        @Override
        public List<Shipment> findAll(Collection<UUID> orderIds) {
            findAllCalls.add(Set.copyOf(orderIds));
            return orderIds.stream().map(stored::get).filter(Objects::nonNull).toList();
        }

        @Override
        public List<Shipment> findByRouteAndStop(UUID routeId, int stopSeq) {
            // 이 유스케이스는 stop 으로 찾지 않는다 — 개정은 주문 id 로 온다.
            throw new UnsupportedOperationException("개정 반영은 stop 으로 찾지 않습니다");
        }

        @Override
        public void insert(Shipment shipment) {
            inserted.add(shipment.orderId());
            stored.put(shipment.orderId(), shipment);
        }

        @Override
        public void update(Shipment shipment) {
            updated.add(shipment.orderId());
            stored.put(shipment.orderId(), shipment);
        }
    }
}
