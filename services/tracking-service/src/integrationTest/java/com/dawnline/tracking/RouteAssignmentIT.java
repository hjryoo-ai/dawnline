package com.dawnline.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.AssignedStop;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.Outcome;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.RouteAssignment;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code route.assigned} 반영을 DB 까지 (DESIGN.md §5.4, §8.5, ADR-045).
 *
 * <p>유스케이스를 <strong>트랜잭션 템플릿 안에서</strong> 부른다 — 운영에서 그것을 여는 것은
 * {@code IdempotentConsumer} 이고(불변규칙 2), 여기서 흉내 내지 않으면 {@code persist} 가
 * 트랜잭션 없이 불린다. 호출마다 트랜잭션을 따로 여는 것도 운영과 같다: 개정 둘은 서로 다른
 * 메시지이고, 한 트랜잭션에 묶으면 낙관적 락 버전이 오르는 자리를 볼 수 없다.
 *
 * <h2>픽스처는 자기 행만 만든다</h2>
 * 라우트·주문 id 를 테스트마다 새로 뽑고({@link Ids#newId()}), 만든 행을 {@code @AfterEach} 에서
 * 지운다. 공유 시드 행을 고치고 되돌리는 형태가 아니므로 실행 순서에도 병렬에도 기대지 않는다
 * (CLAUDE.md — 픽스처는 되돌리지 말고 만들고 지운다).
 */
@SpringBootTest
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("route.assigned 반영")
class RouteAssignmentIT extends TrackingIntegrationTestBase {

    /** 캠프는 라우트의 성질이다 — route_revisions 에 남아 at-risk 메트릭의 camp 라벨이 된다 (§9.1). */
    private static final UUID CAMP = UUID.randomUUID();

    @Autowired
    private ApplyRouteAssignmentUseCase applyRouteAssignment;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    private TransactionTemplate transactions;
    private Instant arrival;
    private Instant promisedEnd;
    private final Set<UUID> createdOrders = new LinkedHashSet<>();
    private final Set<UUID> createdRoutes = new LinkedHashSet<>();

    /**
     * 이 IT 가 자기 자리에서 끈다 (CLAUDE.md — 기반은 이 속성에 의견을 갖지 않는다).
     *
     * <ul>
     *   <li>outbox 릴레이: 이 IT 는 아무것도 발행하지 않는다. 켜 두면 브로커 없는 컨텍스트에서
     *       advisory lock 만 쥐고 있게 된다.</li>
     *   <li>파티션 스케줄러: {@code shipment_events} 를 건드리지 않는다.</li>
     *   <li>Kafka 리스너 컨테이너: 브로커를 띄우지 않는다. 이 IT 가 보는 것은 리스너 <em>뒤</em>의
     *       규칙이고, 브로커까지 도는 검사는 5-1b 의 발행 IT 가 맡는다.</li>
     * </ul>
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void 공유_자원을_끈다(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("dawnline.tracking.partitions.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        // 시각은 주입된 시계에서 뽑는다 (불변규칙 12). 리터럴을 쓰면 저장 정밀도(마이크로초)와
        // 어긋난 나노초가 조용히 잘려 비교가 실패한다 — libs/messaging 의 Clock 은 이미 잘려 있다.
        arrival = clock.instant().plus(Duration.ofMinutes(30));
        promisedEnd = clock.instant().plus(Duration.ofHours(3));
    }

    @AfterEach
    void 만든_행을_지운다() {
        createdOrders.forEach(orderId -> jdbc.update("DELETE FROM shipments WHERE order_id = ?", orderId));
        createdRoutes.forEach(routeId ->
                jdbc.update("DELETE FROM route_revisions WHERE route_id = ?", routeId));
        createdOrders.clear();
        createdRoutes.clear();
    }

    // --- 최초 확정 -----------------------------------------------------------

    @Test
    void 개정은_stop_마다_배송을_만든다() {
        UUID route = newRoute();
        UUID first = newOrder();
        UUID second = newOrder();

        Outcome outcome = apply(new RouteAssignment(route, 1, CAMP, List.of(
                stop(1, List.of(first), Set.of()),
                stop(2, List.of(second), Set.of()))));

        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.APPLIED);
        assertThat(outcome.created()).isEqualTo(2);

        ShipmentRow row = shipment(first);
        assertThat(row.routeId()).isEqualTo(route);
        assertThat(row.stopSeq()).isEqualTo(1);
        assertThat(row.status()).isEqualTo("SCHEDULED");
        assertThat(row.plannedArrival()).isEqualTo(arrival);
        assertThat(row.etaAt()).as("§5.4 — eta_at 의 초기값은 planned_arrival 이다").isEqualTo(arrival);
        assertThat(row.promisedEnd()).isEqualTo(promisedEnd);
        assertThat(row.deliveredAt()).isNull();
        assertThat(shipment(second).stopSeq()).isEqualTo(2);
    }

    @Test
    void 한_stop_의_여러_주문이_각각_행이_된다() {
        // StopMerger 가 같은 지점의 주문을 묶는다(§6.5 1단계). shipments 의 PK 는 order_id 다.
        UUID route = newRoute();
        UUID first = newOrder();
        UUID second = newOrder();

        apply(new RouteAssignment(route, 1, CAMP, List.of(stop(3, List.of(first, second), Set.of()))));

        assertThat(shipment(first).stopSeq()).isEqualTo(3);
        assertThat(shipment(second).stopSeq()).isEqualTo(3);
    }

    // --- 개정 비교 (ADR-045) --------------------------------------------------

    @Test
    void 지난_개정은_아무것도_바꾸지_않는다() {
        UUID route = newRoute();
        UUID order = newOrder();
        apply(new RouteAssignment(route, 2, CAMP, List.of(stop(1, List.of(order), Set.of()))));

        Instant movedArrival = arrival.plus(Duration.ofHours(2));
        Outcome outcome = apply(new RouteAssignment(route, 1, CAMP,
                List.of(stop(9, List.of(order), Set.of(), movedArrival, promisedEnd))));

        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.STALE);
        ShipmentRow row = shipment(order);
        assertThat(row.stopSeq()).isEqualTo(1);
        assertThat(row.plannedArrival()).isEqualTo(arrival);
        assertThat(revisionOf(route)).isEqualTo(2);
    }

    @Test
    void 같은_개정을_다시_받으면_무시된다() {
        // 재계획이 같은 번호를 새 eventId 로 다시 발행하면 processed_events 는 통과한다.
        // 같은 번호의 재발행은 새 정보를 담지 않는다 (route.assigned.v1 의 revision 설명).
        UUID route = newRoute();
        UUID order = newOrder();
        apply(new RouteAssignment(route, 3, CAMP, List.of(stop(1, List.of(order), Set.of()))));

        Outcome outcome = apply(new RouteAssignment(route, 3, CAMP,
                List.of(stop(4, List.of(order), Set.of()))));

        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.STALE);
        assertThat(shipment(order).stopSeq()).isEqualTo(1);
    }

    @Test
    void route_revisions_는_라우트당_한_행이다() {
        // shipments 에서 MAX 로 유도하는 안을 버린 이유가 이 표의 존재다 (ADR-045).
        UUID route = newRoute();
        UUID order = newOrder();

        apply(new RouteAssignment(route, 1, CAMP, List.of(stop(1, List.of(order), Set.of()))));
        apply(new RouteAssignment(route, 2, CAMP, List.of(stop(1, List.of(order), Set.of()))));

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM route_revisions WHERE route_id = ?", Integer.class, route);
        assertThat(rows).isEqualTo(1);
        assertThat(revisionOf(route)).isEqualTo(2);
    }

    // --- 부재는 값이 아니다 ---------------------------------------------------

    @Test
    void A_의_개정에_없는_shipment_는_건드리지_않는다() {
        // §6.8 의 relocate 가 주문을 A → B 로 옮기면, dispatch 는 A 의 개정(그 주문이 빠진)과
        // B 의 개정(그 주문이 실린)을 **서로 다른 파티션**으로 발행한다 — 둘 사이에 순서가 없다.
        // B 가 먼저 처리되면 뒤에 온 A 의 개정에는 그 주문이 없고, 그때 "없으니 정리" 를 하면
        // 방금 끝난 이동이 취소된다. 부재는 값이 아니다 (ADR-026).
        //
        // **이 순서는 관대한 쪽이다.** 정리를 `route_id = A` 로 묻는 구현이라면 그 주문은 이미
        // B 라서 걸리지 않는다 — 그 구현을 잡는 것은 아래의 반대 순서 테스트다. 여기가 막는 것은
        // 라우트를 묻지 않는 정리(이전 개정의 주문 목록과 비교하는 종류)이고, 두 순서를 함께
        // 두는 이유는 하나만으로는 "무엇이 우연히 통과시키는가" 를 말할 수 없기 때문이다.
        UUID routeA = newRoute();
        UUID routeB = newRoute();
        UUID moved = newOrder();
        UUID stayed = newOrder();

        // 1) A 의 최초 확정 — 둘 다 A 에 있다.
        apply(new RouteAssignment(routeA, 1, CAMP, List.of(
                stop(1, List.of(moved), Set.of()),
                stop(2, List.of(stayed), Set.of()))));

        // 2) B 의 개정이 먼저 도착해 moved 를 가져간다.
        Instant movedArrival = arrival.plus(Duration.ofMinutes(45));
        apply(new RouteAssignment(routeB, 1, CAMP,
                List.of(stop(1, List.of(moved), Set.of(), movedArrival, promisedEnd))));
        assertThat(shipment(moved).routeId())
                .as("전제 — 이동이 실제로 일어났다")
                .isEqualTo(routeB);

        // 3) 그 다음 A 의 개정이 도착한다. moved 는 이 페이로드에 없다.
        Outcome outcome = apply(new RouteAssignment(routeA, 2, CAMP,
                List.of(stop(1, List.of(stayed), Set.of()))));

        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.APPLIED);
        ShipmentRow movedRow = shipment(moved);
        assertThat(movedRow.routeId())
                .as("A 의 개정에 없다는 것은 「지워라」가 아니라 「여기서는 할 말이 없다」다")
                .isEqualTo(routeB);
        assertThat(movedRow.stopSeq()).isEqualTo(1);
        assertThat(movedRow.plannedArrival()).isEqualTo(movedArrival);
        assertThat(movedRow.status()).isEqualTo("SCHEDULED");
        assertThat(shipment(stayed).stopSeq()).as("개정에 있는 쪽은 갱신된다").isEqualTo(1);
    }

    @Test
    void A_의_개정이_먼저_와도_이동할_주문을_죽이지_않는다() {
        // 위 테스트의 <strong>반대 순서</strong>다. 그리고 이쪽이 실제로 위험하다: A 의 개정이
        // 먼저 오면 그 주문은 아직 route_id = A 라, "이 라우트의 shipment 중 개정에 없는 것"
        // 을 묻는 정리 코드에 그대로 잡힌다. 잡혀서 취소되면 뒤에 온 B 의 개정은 종결 상태를
        // 만나 아무것도 못 하고(§5.4), 이동은 영영 사라진다.
        UUID routeA = newRoute();
        UUID routeB = newRoute();
        UUID moved = newOrder();
        UUID stayed = newOrder();
        apply(new RouteAssignment(routeA, 1, CAMP, List.of(
                stop(1, List.of(moved), Set.of()),
                stop(2, List.of(stayed), Set.of()))));

        // 1) A 의 개정이 먼저 — moved 가 빠졌다. 아직 moved 의 route_id 는 A 다.
        apply(new RouteAssignment(routeA, 2, CAMP, List.of(stop(1, List.of(stayed), Set.of()))));
        assertThat(shipment(moved).status())
                .as("A 의 개정에서 빠진 것은 「취소」가 아니다 — 아직 아무 일도 일어나지 않았다")
                .isEqualTo("SCHEDULED");

        // 2) 그 다음 B 의 개정이 도착해 데려간다.
        Instant movedArrival = arrival.plus(Duration.ofMinutes(45));
        Outcome outcome = apply(new RouteAssignment(routeB, 1, CAMP,
                List.of(stop(1, List.of(moved), Set.of(), movedArrival, promisedEnd))));

        assertThat(outcome.keptTerminal())
                .as("1) 에서 죽었다면 여기서 종결 상태로 세어지고 이동이 사라진다")
                .isZero();
        ShipmentRow movedRow = shipment(moved);
        assertThat(movedRow.routeId()).isEqualTo(routeB);
        assertThat(movedRow.status()).isEqualTo("SCHEDULED");
        assertThat(movedRow.plannedArrival()).isEqualTo(movedArrival);
    }

    // --- 종결 상태 -----------------------------------------------------------

    @Test
    void 종결된_배송은_개정이_와도_그대로다() {
        UUID route = newRoute();
        UUID delivered = newOrder();
        UUID pending = newOrder();
        apply(new RouteAssignment(route, 1, CAMP, List.of(
                stop(1, List.of(delivered), Set.of()),
                stop(2, List.of(pending), Set.of()))));
        markCompleted(delivered);

        Instant movedArrival = arrival.plus(Duration.ofHours(1));
        Outcome outcome = apply(new RouteAssignment(route, 2, CAMP, List.of(
                stop(5, List.of(delivered), Set.of(), movedArrival, promisedEnd),
                stop(6, List.of(pending), Set.of(), movedArrival, promisedEnd))));

        assertThat(outcome.keptTerminal()).isEqualTo(1);
        assertThat(outcome.revised()).isEqualTo(1);
        ShipmentRow kept = shipment(delivered);
        assertThat(kept.status()).isEqualTo("COMPLETED");
        assertThat(kept.stopSeq()).isEqualTo(1);
        assertThat(kept.plannedArrival()).isEqualTo(arrival);
        assertThat(shipment(pending).stopSeq()).isEqualTo(6);
    }

    // --- 취소 (ADR-026) -------------------------------------------------------

    @Test
    void 통합된_stop_의_부분_취소는_주문_단위로_남는다() {
        UUID route = newRoute();
        UUID cancelled = newOrder();
        UUID alive = newOrder();
        apply(new RouteAssignment(route, 1, CAMP, List.of(stop(1, List.of(cancelled, alive), Set.of()))));

        Outcome outcome = apply(new RouteAssignment(route, 2, CAMP,
                List.of(stop(1, List.of(cancelled, alive), Set.of(cancelled)))));

        assertThat(outcome.cancelled()).isEqualTo(1);
        assertThat(shipment(cancelled).status()).isEqualTo("CANCELLED");
        assertThat(shipment(alive).status())
                .as("같은 stop 이라고 함께 죽지 않는다 — shipments 의 PK 는 order_id 다")
                .isEqualTo("SCHEDULED");
    }

    @Test
    void 같은_취소가_다시_실려_와도_상태가_그대로다() {
        // 취소된 stop 은 페이로드에서 지우지 않으므로 개정마다 계속 실려 온다 (ADR-026).
        UUID route = newRoute();
        UUID order = newOrder();
        apply(new RouteAssignment(route, 1, CAMP, List.of(stop(1, List.of(order), Set.of(order)))));

        Outcome outcome = apply(new RouteAssignment(route, 2, CAMP,
                List.of(stop(1, List.of(order), Set.of(order)))));

        assertThat(outcome.keptTerminal()).isEqualTo(1);
        assertThat(outcome.cancelled()).isZero();
        assertThat(shipment(order).status()).isEqualTo("CANCELLED");
    }

    // --- 낙관적 락 -----------------------------------------------------------

    @Test
    void 갱신마다_낙관적_락_버전이_오른다() {
        // @Version 매핑이 실제로 걸려 있는지를 보는 유일한 자리다. 안 걸려 있어도 이 서비스의
        // 다른 검사는 전부 통과한다 — 덮어쓰기는 경합이 있을 때만 드러난다.
        UUID route = newRoute();
        UUID order = newOrder();
        apply(new RouteAssignment(route, 1, CAMP, List.of(stop(1, List.of(order), Set.of()))));
        long created = shipment(order).version();

        apply(new RouteAssignment(route, 2, CAMP, List.of(stop(2, List.of(order), Set.of()))));

        assertThat(shipment(order).version()).isGreaterThan(created);
    }

    // --- 픽스처 --------------------------------------------------------------

    private Outcome apply(RouteAssignment assignment) {
        return transactions.execute(status -> applyRouteAssignment.apply(assignment));
    }

    private AssignedStop stop(int seq, List<UUID> orderIds, Set<UUID> cancelled) {
        return stop(seq, orderIds, cancelled, arrival, promisedEnd);
    }

    private AssignedStop stop(int seq, List<UUID> orderIds, Set<UUID> cancelled,
            Instant plannedArrival, Instant end) {
        return new AssignedStop(seq, orderIds, cancelled, plannedArrival, end);
    }

    private UUID newOrder() {
        UUID orderId = Ids.newId();
        createdOrders.add(orderId);
        return orderId;
    }

    private UUID newRoute() {
        UUID routeId = Ids.newId();
        createdRoutes.add(routeId);
        return routeId;
    }

    private void markCompleted(UUID orderId) {
        // 스캔 API 는 다음 단계다(5-1a). 여기서는 자기 픽스처 행 하나를 직접 종결로 옮긴다 —
        // 공유 시드가 아니라 이 테스트가 만든 행이라 반경이 그 행 하나다.
        int updated = jdbc.update(
                "UPDATE shipments SET status = 'COMPLETED', delivered_at = ? WHERE order_id = ?",
                OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC), orderId);
        assertThat(updated).as("전제 — 종결로 옮길 행이 있다").isEqualTo(1);
    }

    private int revisionOf(UUID routeId) {
        Integer revision = jdbc.queryForObject(
                "SELECT revision FROM route_revisions WHERE route_id = ?", Integer.class, routeId);
        assertThat(revision).isNotNull();
        return revision;
    }

    private ShipmentRow shipment(UUID orderId) {
        List<ShipmentRow> rows = new ArrayList<>(jdbc.query("""
                SELECT route_id, stop_seq, status, planned_arrival, eta_at, promised_end,
                       delivered_at, version
                  FROM shipments
                 WHERE order_id = ?
                """, (rs, rowNum) -> new ShipmentRow(
                        rs.getObject(1, UUID.class),
                        rs.getShort(2),
                        rs.getString(3),
                        instant(rs.getObject(4, OffsetDateTime.class)),
                        instant(rs.getObject(5, OffsetDateTime.class)),
                        instant(rs.getObject(6, OffsetDateTime.class)),
                        instant(rs.getObject(7, OffsetDateTime.class)),
                        rs.getLong(8)),
                orderId));
        assertThat(rows).as("배송 행이 있어야 한다: orderId=%s", orderId).hasSize(1);
        return rows.getFirst();
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** {@code shipments} 한 행. */
    private record ShipmentRow(UUID routeId, int stopSeq, String status, Instant plannedArrival,
            Instant etaAt, Instant promisedEnd, @Nullable Instant deliveredAt, long version) {
    }
}
