package com.dawnline.tracking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.dawnline.common.Ids;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.common.error.ValidationException;
import com.dawnline.tracking.application.port.in.RecordScanUseCase.OrderScan;
import com.dawnline.tracking.application.port.in.RecordScanUseCase.ScanCommand;
import com.dawnline.tracking.application.port.in.RecordScanUseCase.ScanResult;
import com.dawnline.tracking.application.port.out.ShipmentEvents;
import com.dawnline.tracking.application.port.out.ShipmentRepository;
import com.dawnline.tracking.domain.ScanOutcome;
import com.dawnline.tracking.domain.ScanType;
import com.dawnline.tracking.domain.Shipment;
import com.dawnline.tracking.domain.ShipmentEvent;
import com.dawnline.tracking.domain.ShipmentStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기사 스캔 적용 규칙 (DESIGN.md §5.4, §8.5).
 *
 * <p>명령은 <strong>송장으로</strong> 온다 — {@code routeId}·{@code stopSeq} 는 확인용이다
 * (ADR-047 결정 1). 그래서 「요청이 말한 번호」와 「배송이 지금 있는 번호」가 다른 경우가
 * 이 파일의 새 축이고, 기대값은 언제나 <em>뒤쪽</em>이다.
 *
 * <p>시각 픽스처는 전부 <strong>주입된 시계에서 파생</strong>한다(CLAUDE.md). 완료 시각은
 * 단말이 말한 시각이 그대로 들어가는 자리라, 리터럴을 쓰면 「어디서 온 값인가」가 흐려진다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("기사 스캔")
class RecordScanServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T22:05:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final UUID ROUTE = UUID.randomUUID();
    /** 재계획이 주문을 옮겨 간 라우트. 기사는 아직 이 번호를 모른다. */
    private static final UUID OTHER_ROUTE = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID SIBLING = UUID.randomUUID();
    /** 요청이 싣는 송장들 — 이 열쇠로 찾는다 (ADR-047 결정 1). */
    private static final List<UUID> ORDERS = List.of(ORDER, SIBLING);
    private static final int SEQ = 3;

    private static final UUID CAMP = UUID.randomUUID();
    /** §5.4 의 at-risk 여유. */
    private static final Duration MARGIN = Duration.ofMinutes(15);
    private static final Instant DEPARTURE = NOW.plus(Duration.ofMinutes(5));
    private static final Instant ARRIVAL = NOW.plus(Duration.ofMinutes(20));
    private static final Instant PROMISED_END = NOW.plus(Duration.ofHours(2));

    private InMemoryShipments shipments;
    private FixedRevisions revisions;
    private RecordingDelivery delivery;
    private OpenCooldown cooldown;
    private RecordingEvents events;
    private MeterRegistry meters;
    private RecordScanService service;

    @BeforeEach
    void setUp() {
        shipments = new InMemoryShipments();
        events = new RecordingEvents();
        meters = new SimpleMeterRegistry();
        revisions = new FixedRevisions();
        delivery = new RecordingDelivery();
        TrackingMetrics metrics = new TrackingMetrics(meters);
        cooldown = new OpenCooldown();
        service = new RecordScanService(shipments, events, delivery,
                new EtaPropagator(shipments, revisions),
                new AtRiskDetector(revisions, cooldown, delivery, metrics, CLOCK, MARGIN),
                metrics, new Ids(CLOCK, RandomGenerator.getDefault()));
    }

    // --- 적용 ---------------------------------------------------------------

    @Test
    void 스캔은_그_stop_의_배송_전부에_적용된다() {
        // StopMerger 가 같은 지점의 주문을 묶는다(§6.5 1단계). 기사는 한 번 찍고 주문은 둘이다.
        shipments.put(scheduled(ORDER));
        shipments.put(scheduled(SIBLING));

        ScanResult result = service.record(scan(ScanType.ARRIVED));

        assertThat(result.orders()).extracting(OrderScan::outcome)
                .containsExactly(ScanOutcome.APPLIED, ScanOutcome.APPLIED);
        assertThat(shipments.stored.get(ORDER).status()).isEqualTo(ShipmentStatus.ARRIVED);
        assertThat(shipments.stored.get(SIBLING).status()).isEqualTo(ShipmentStatus.ARRIVED);
        assertThat(shipments.updated).containsExactlyInAnyOrder(ORDER, SIBLING);
    }

    @Test
    void 적용된_주문만_사건으로_남는다() {
        shipments.put(scheduled(ORDER));

        service.record(scan(ScanType.ARRIVED));

        assertThat(events.appended).singleElement().satisfies(event -> {
            assertThat(event.orderId()).isEqualTo(ORDER);
            assertThat(event.routeId()).isEqualTo(ROUTE);
            assertThat(event.type()).isEqualTo(ScanType.ARRIVED);
            assertThat(event.occurredAt()).isEqualTo(NOW);
            assertThat(event.failureReason()).isNull();
        });
    }

    @Test
    void 건너뛴_스캔도_받는다() {
        // 도착 스캔을 빼먹고 완료를 찍는 일은 흔하고, 그때 물건은 실제로 전달됐다 (§5.4 축 규칙).
        shipments.put(scheduled(ORDER));

        ScanResult result = service.record(scan(ScanType.COMPLETED));

        assertThat(result.count(ScanOutcome.APPLIED)).isEqualTo(1);
        assertThat(shipments.stored.get(ORDER).status()).isEqualTo(ShipmentStatus.COMPLETED);
    }

    @Test
    void 완료_시각은_단말이_말한_시각이다() {
        // 서버가 받은 시각을 쓰면 정시율(§8.1)이 처리 지연만큼 어긋난다.
        shipments.put(scheduled(ORDER));
        Instant scannedAt = NOW.minus(Duration.ofMinutes(7));

        service.record(new ScanCommand(ROUTE, SEQ, ORDERS, ScanType.COMPLETED, scannedAt, null, null, null));

        assertThat(shipments.stored.get(ORDER).deliveredAt()).isEqualTo(scannedAt);
    }

    @Test
    void 좌표는_그대로_사건에_실린다() {
        shipments.put(scheduled(ORDER));

        service.record(new ScanCommand(ROUTE, SEQ, ORDERS, ScanType.ARRIVED, NOW, 37.4979, 127.0276, null));

        assertThat(events.appended).singleElement().satisfies(event -> {
            assertThat(event.lat()).isEqualTo(37.4979);
            assertThat(event.lng()).isEqualTo(127.0276);
        });
    }

    // --- 멱등 (§8.5 — 키는 상태 머신이다) --------------------------------------

    @Test
    void 같은_스캔이_다시_와도_상태를_바꾸지_않는다() {
        shipments.put(scheduled(ORDER));
        service.record(scan(ScanType.ARRIVED));
        events.appended.clear();

        ScanResult result = service.record(scan(ScanType.ARRIVED));

        assertThat(result.count(ScanOutcome.STALE)).isEqualTo(1);
        assertThat(shipments.stored.get(ORDER).status()).isEqualTo(ShipmentStatus.ARRIVED);
        assertThat(events.appended)
                .as("중복 스캔은 사건이 아니다 — 남기면 「어느 행이 무언가를 바꿨나」를 알려면 "
                        + "상태 머신을 다시 구현해야 한다")
                .isEmpty();
    }

    @Test
    void 역행_스캔은_무시된다() {
        // 순서가 뒤바뀌어 늦게 온 도착 스캔이다. 사실은 이미 일어났다 (ADR-017).
        shipments.put(scheduled(ORDER));
        service.record(scan(ScanType.COMPLETED));

        ScanResult result = service.record(scan(ScanType.ARRIVED));

        assertThat(result.count(ScanOutcome.STALE)).isEqualTo(1);
        assertThat(shipments.stored.get(ORDER).status()).isEqualTo(ShipmentStatus.COMPLETED);
    }

    // --- 취소 뒤 스캔 --------------------------------------------------------

    @Test
    void 취소된_배송의_스캔은_무시하되_센다() {
        shipments.put(cancelled(ORDER));

        ScanResult result = service.record(scan(ScanType.COMPLETED));

        assertThat(result.count(ScanOutcome.AFTER_CANCEL)).isEqualTo(1);
        assertThat(shipments.stored.get(ORDER).status()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(events.appended).isEmpty();
        assertThat(scanAfterCancelCount()).isEqualTo(1.0);
    }

    @Test
    void 통합된_stop_에서_주문마다_다른_답이_나온다() {
        // 세 주문이 실린 stop 에서 하나만 취소되는 일이 실재한다 (ADR-026 후속 정정).
        // 합쳐서 하나로 답하면 기사 단말이 「무엇이 안 됐는가」를 알 수 없다.
        shipments.put(scheduled(ORDER));
        shipments.put(cancelled(SIBLING));

        ScanResult result = service.record(scan(ScanType.COMPLETED));

        assertThat(result.orders()).containsExactly(
                new OrderScan(ORDER, ScanOutcome.APPLIED, ShipmentStatus.COMPLETED),
                new OrderScan(SIBLING, ScanOutcome.AFTER_CANCEL, ShipmentStatus.CANCELLED));
        assertThat(events.appended).extracting(ShipmentEvent::orderId).containsExactly(ORDER);
        assertThat(scanAfterCancelCount()).isEqualTo(1.0);
    }

    @Test
    void 사건_적재가_실패하면_카운터를_올리지_않는다() {
        // 카운터는 트랜잭션을 모른다. 먼저 올리면 롤백된 스캔의 숫자만 남고, 「취소 뒤 스캔이
        // 늘었다」는 알림이 실제로는 파티션이 없어서 났다는 뜻이 된다 — 대시보드에서 풀리지 않는다.
        shipments.put(scheduled(ORDER));
        shipments.put(cancelled(SIBLING));
        events.failWith = new IllegalStateException("no partition of relation \"shipment_events\"");

        assertThatThrownBy(() -> service.record(scan(ScanType.COMPLETED)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(scanAfterCancelCount()).isZero();
    }

    // --- 거절 ---------------------------------------------------------------

    @Test
    void 배송이_없으면_찾을_수_없다고_답한다() {
        // 아직 route.assigned 를 소비하지 않은 창일 수 있어 단말은 그대로 재시도하면 된다.
        assertThatThrownBy(() -> service.record(scan(ScanType.ARRIVED)))
                .isInstanceOf(NotFoundException.class)
                .as("무엇을 못 찾았는지는 라우트가 아니라 주문으로 말한다 (ADR-047 결정 1)")
                .hasMessageContaining(ORDER.toString());
        assertThat(events.appended).isEmpty();
    }

    @Test
    void 사유는_FAILED_에만_붙는다() {
        shipments.put(scheduled(ORDER));

        assertThatThrownBy(() -> service.record(
                new ScanCommand(ROUTE, SEQ, ORDERS, ScanType.COMPLETED, NOW, null, null, "부재")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("failureReason");
    }

    @Test
    void 거절_메시지에_사유_본문을_싣지_않는다() {
        // 자유 텍스트라 개인정보가 섞일 수 있고, 오류 응답과 로그에 그대로 실린다 (§9.3).
        shipments.put(scheduled(ORDER));
        String reason = "우편함 비밀번호 1234, 옆집에 맡김";

        assertThatThrownBy(() -> service.record(
                new ScanCommand(ROUTE, SEQ, ORDERS, ScanType.ARRIVED, NOW, null, null, reason)))
                .isInstanceOf(ValidationException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("1234"));
    }

    @Test
    void 실패_사유는_사건에_실린다() {
        shipments.put(scheduled(ORDER));

        service.record(new ScanCommand(ROUTE, SEQ, ORDERS, ScanType.FAILED, NOW, null, null, "부재"));

        assertThat(events.appended).singleElement()
                .satisfies(event -> assertThat(event.failureReason()).isEqualTo("부재"));
    }

    @Test
    void stop_순번은_1_부터다() {
        // 경로 변수라 클라이언트가 만든 값이다. IllegalArgumentException 이면 500 으로 나간다.
        assertThatThrownBy(() -> new ScanCommand(ROUTE, 0, ORDERS, ScanType.ARRIVED, NOW, null, null, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void record_에_Transactional_이_붙어_있다() throws NoSuchMethodException {
        // ArchUnit 규칙 5 는 어노테이션의 <위치>만 본다 — 없어진 것은 잡지 못한다.
        // 이 유스케이스는 HTTP 에서 직접 불리므로 감싸 주는 IdempotentConsumer 가 없다.
        assertThat(RecordScanService.class.getMethod("record", ScanCommand.class)
                .isAnnotationPresent(Transactional.class))
                .as("없으면 상태 갱신과 사건 적재가 서로 다른 트랜잭션이 된다")
                .isTrue();
    }

    // --- 픽스처 --------------------------------------------------------------

    private double scanAfterCancelCount() {
        try {
            return meters.get(TrackingMetrics.SCAN_AFTER_CANCEL).counter().count();
        } catch (MeterNotFoundException e) {
            return 0.0;
        }
    }

    // --- delivery.status 발행 (Phase 5-1b) --------------------------------------

    @Test
    void 옮겨진_주문만_한_건으로_발행한다() {
        // stop 단위 한 건이다. 주문마다 내보내면 한 번의 방문이 여러 사건으로 쪼개지고,
        // 소비자가 그것을 다시 합쳐야 한다 (§4.1, §6.5 1단계).
        shipments.put(scheduled(ORDER));
        shipments.put(scheduled(SIBLING));

        service.record(scan(ScanType.COMPLETED));

        assertThat(delivery.sent).singleElement().satisfies(sent -> {
            assertThat(sent.routeId()).isEqualTo(ROUTE);
            assertThat(sent.stopSeq()).isEqualTo(SEQ);
            assertThat(sent.orderIds()).containsExactlyInAnyOrder(ORDER, SIBLING);
            assertThat(sent.type()).isEqualTo(ScanType.COMPLETED);
            assertThat(sent.occurredAt()).isEqualTo(NOW);
        });
    }

    @Test
    void 캠프_출발은_브로커로_나가지_않는다() {
        // 한 사실을 stop 수만큼 반복해 말하는 꼴이고, order-service 의 상태 머신은
        // DISPATCHED 로 그 구간을 이미 덮는다 (ScanType.isPublished()).
        shipments.put(scheduled(ORDER));

        service.record(departure(NOW));

        assertThat(delivery.sent).isEmpty();
    }

    @Test
    void 아무것도_옮기지_못한_스캔은_발행하지_않는다() {
        // 전부 STALE 이거나 AFTER_CANCEL 이다 — 소비자에게 말할 새 사실이 없다.
        shipments.put(scheduled(ORDER));
        service.record(scan(ScanType.COMPLETED));
        delivery.sent.clear();

        service.record(scan(ScanType.COMPLETED));

        assertThat(delivery.sent).isEmpty();
    }

    // --- 캠프 출발과 편차 전파 (Phase 5-1b) -------------------------------------

    @Test
    void 캠프_출발은_라우트_전체에_적용된다() {
        // 기사는 캠프를 한 번 떠나고, 그 순간 그 라우트의 모든 배송이 길 위에 있다. stop
        // 하나만 옮기면 나머지는 SCHEDULED 로 남아 「아직 출발하지 않은 배송」처럼 보인다.
        shipments.put(scheduled(ORDER));
        shipments.put(Shipment.scheduled(SIBLING, ROUTE, SEQ + 2, ARRIVAL.plus(Duration.ofMinutes(20)),
                PROMISED_END));

        ScanResult result = service.record(departure(NOW));

        assertThat(result.orders()).extracting(OrderScan::orderId)
                .as("응답에도 라우트의 모든 주문이 들어온다 — 단말이 무엇이 옮겨졌는지 알아야 한다")
                .containsExactlyInAnyOrder(ORDER, SIBLING);
        assertThat(shipments.stored.values()).extracting(Shipment::status)
                .containsOnly(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void 배송이_없는_라우트의_캠프_출발은_404_다() {
        assertThatThrownBy(() -> service.record(departure(NOW)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 늦은_출발은_뒤따르는_stop_의_ETA_를_민다() {
        // 늦은 출발은 가장 흔한 지연 원인이고 첫 도착 스캔 전에 이미 알 수 있다 (§5.4).
        shipments.put(scheduled(ORDER));
        Duration late = Duration.ofMinutes(12);

        service.record(departure(DEPARTURE.plus(late)));

        assertThat(shipments.stored.get(ORDER).etaAt()).isEqualTo(ARRIVAL.plus(late));
    }

    @Test
    void 도착_스캔의_편차는_그_stop_의_계획_도착에서_잰다() {
        shipments.put(scheduled(ORDER));
        shipments.put(Shipment.scheduled(SIBLING, ROUTE, SEQ + 1,
                ARRIVAL.plus(Duration.ofMinutes(10)), PROMISED_END));
        Duration late = Duration.ofMinutes(9);

        service.record(new ScanCommand(ROUTE, SEQ, List.of(ORDER), ScanType.ARRIVED,
                ARRIVAL.plus(late), null, null, null));

        assertThat(shipments.stored.get(SIBLING).etaAt())
                .isEqualTo(ARRIVAL.plus(Duration.ofMinutes(10)).plus(late));
        assertThat(shipments.stored.get(ORDER).etaAt())
                .as("스캔이 난 stop 자신은 옮기지 않는다")
                .isEqualTo(ARRIVAL);
    }

    // --- 번호는 확인용이다 (ADR-047 결정 1) ------------------------------------

    @Test
    void 요청의_번호가_옛것이어도_주문으로_찾아_적용한다() {
        // 기사는 개정 r 의 (ROUTE, SEQ) 로 찍었고 우리는 r+1 을 적용해 그 주문을 옮겼다.
        // 번호로 찾으면 404 이거나 엉뚱한 주문을 완료로 적는다 — 둘 다 기사가 고칠 수 없다.
        shipments.put(Shipment.scheduled(ORDER, OTHER_ROUTE, 9, ARRIVAL, PROMISED_END));

        ScanResult result = service.record(scan(ScanType.COMPLETED));

        assertThat(result.count(ScanOutcome.APPLIED)).isEqualTo(1);
        assertThat(shipments.stored.get(ORDER).status()).isEqualTo(ShipmentStatus.COMPLETED);
        assertThat(relocateCount())
                .as("무시하지 않고 적용한 뒤 센다 — 이 값이 기사와 개정이 어긋난 창의 크기다")
                .isEqualTo(1.0);
    }

    @Test
    void 자리가_맞는_스캔은_어긋남으로_세지_않는다() {
        // 평상시에 오르면 그 카운터는 아무것도 말하지 않는다.
        shipments.put(scheduled(ORDER));

        service.record(scan(ScanType.COMPLETED));

        assertThat(relocateCount()).isZero();
    }

    @Test
    void 재배치된_건의_발행은_배송이_지금_있는_좌표로_나간다() {
        // 옛 좌표를 실어 보내면 dispatch 의 확인용 컨텍스트가 틀린 값을 받고, 저쪽의
        // dawnline_status_after_relocate_total 이 우리 탓으로 오른다.
        shipments.put(Shipment.scheduled(ORDER, OTHER_ROUTE, 9, ARRIVAL, PROMISED_END));

        service.record(scan(ScanType.COMPLETED));

        assertThat(delivery.sent).singleElement().satisfies(sent -> {
            assertThat(sent.routeId()).isEqualTo(OTHER_ROUTE);
            assertThat(sent.stopSeq()).isEqualTo(9);
            assertThat(sent.orderIds()).containsExactly(ORDER);
        });
    }

    @Test
    void 주문들이_지금_다른_stop_에_있으면_발행이_둘이다() {
        // 한 건으로 합치면 둘 중 하나의 좌표가 거짓이 된다.
        shipments.put(scheduled(ORDER));
        shipments.put(Shipment.scheduled(SIBLING, OTHER_ROUTE, 9, ARRIVAL, PROMISED_END));

        service.record(scan(ScanType.COMPLETED));

        assertThat(delivery.sent).hasSize(2)
                .extracting(Published::routeId, Published::stopSeq)
                .containsExactlyInAnyOrder(tuple(ROUTE, SEQ), tuple(OTHER_ROUTE, 9));
    }

    @Test
    void 적재가_실패하면_어긋남도_세지_않는다() {
        // 카운터는 트랜잭션을 모른다. 이 값이 틀리는 방향은 「재계획이 돌고 있다」이고,
        // 그것은 대시보드에서 풀리지 않는 오해다.
        shipments.put(Shipment.scheduled(ORDER, OTHER_ROUTE, 9, ARRIVAL, PROMISED_END));
        events.failWith = new IllegalStateException("no partition of relation \"shipment_events\"");

        assertThatThrownBy(() -> service.record(scan(ScanType.COMPLETED)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(relocateCount()).isZero();
    }

    @Test
    void 캠프_출발에_주문을_실으면_거절한다() {
        // 라우트의 사건이라 열쇠가 라우트다. 조용히 버리면 단말은 자기가 보낸 것이 사라진 줄
        // 모르고, 「어느 주문이 출발했나」를 물은 단말은 라우트 전체를 받은 응답을 오해한다.
        shipments.put(scheduled(ORDER));

        assertThatThrownBy(() -> service.record(new ScanCommand(ROUTE, SEQ, ORDERS,
                ScanType.DEPARTED_CAMP, NOW, null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("orderIds");
        assertThat(shipments.stored.get(ORDER).status()).isEqualTo(ShipmentStatus.SCHEDULED);
    }

    @Test
    void 주문_없는_stop_스캔은_거절한다() {
        assertThatThrownBy(() -> service.record(
                new ScanCommand(ROUTE, SEQ, List.of(), ScanType.ARRIVED, NOW, null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("orderIds");
    }

    // --- 픽스처 (이어서) -------------------------------------------------------

    private static ScanCommand scan(ScanType type) {
        return new ScanCommand(ROUTE, SEQ, ORDERS, type, NOW, null, null, null);
    }

    /** 캠프 출발은 라우트의 사건이라 주문을 싣지 않는다 (ADR-047 결정 1). */
    private static ScanCommand departure(Instant at) {
        return new ScanCommand(ROUTE, SEQ, List.of(), ScanType.DEPARTED_CAMP, at, null, null, null);
    }

    private double relocateCount() {
        try {
            return meters.get(TrackingMetrics.SCAN_AFTER_RELOCATE).counter().count();
        } catch (MeterNotFoundException e) {
            return 0.0;
        }
    }

    private static Shipment scheduled(UUID orderId) {
        return Shipment.scheduled(orderId, ROUTE, SEQ, ARRIVAL, PROMISED_END);
    }

    private static Shipment cancelled(UUID orderId) {
        Shipment shipment = scheduled(orderId);
        shipment.cancel();
        return shipment;
    }

    /** 발행된 {@code delivery.status} 한 건. */
    private record Published(UUID routeId, int stopSeq, List<UUID> orderIds, ScanType type,
            Instant occurredAt, String failureReason) {
    }

    private static final class RecordingDelivery
            implements com.dawnline.tracking.application.port.out.DeliveryEvents {

        private final List<Published> sent = new ArrayList<>();
        private final List<UUID> atRisk = new ArrayList<>();

        @Override
        public void deliveryStatus(UUID routeId, int stopSeq, List<UUID> orderIds, ScanType type,
                Instant occurredAt, String failureReason) {
            sent.add(new Published(routeId, stopSeq, List.copyOf(orderIds), type, occurredAt,
                    failureReason));
        }

        @Override
        public void deliveryAtRisk(UUID routeId, UUID campId, Instant detectedAt,
                Duration deviation, List<Shipment> remaining, Duration margin) {
            atRisk.add(routeId);
        }
    }

    /** 언제나 창을 여는 쿨다운. 쿨다운 자체는 AtRiskDetectorTest 가 본다. */
    private static final class OpenCooldown
            implements com.dawnline.tracking.application.port.out.AtRiskCooldown {

        private boolean open = true;

        @Override
        public boolean tryStart(UUID routeId) {
            return open;
        }
    }

    /** 라우트당 계획값. 이 테스트에서 바뀌지 않는다 — 보는 것은 편차 계산이지 저장이 아니다. */
    private static final class FixedRevisions
            implements com.dawnline.tracking.application.port.out.RouteRevisions {

        private Instant plannedDeparture = DEPARTURE;

        @Override
        public boolean claim(UUID routeId, int revision, UUID campId, Instant departure,
                Instant appliedAt) {
            throw new UnsupportedOperationException("스캔 경로는 선점하지 않습니다");
        }

        @Override
        public java.util.Optional<RoutePlanned> find(UUID routeId) {
            return java.util.Optional.of(new RoutePlanned(CAMP, plannedDeparture));
        }
    }

    private static final class RecordingEvents implements ShipmentEvents {

        private final List<ShipmentEvent> appended = new ArrayList<>();
        private RuntimeException failWith;

        @Override
        public void appendAll(Collection<ShipmentEvent> events) {
            if (failWith != null) {
                throw failWith;
            }
            appended.addAll(events);
        }
    }

    private static final class InMemoryShipments implements ShipmentRepository {

        private final Map<UUID, Shipment> stored = new LinkedHashMap<>();
        private final List<UUID> updated = new ArrayList<>();

        void put(Shipment shipment) {
            stored.put(shipment.orderId(), shipment);
        }

        @Override
        public List<Shipment> findAll(Collection<UUID> orderIds) {
            return orderIds.stream().map(stored::get).filter(Objects::nonNull).toList();
        }

        @Override
        public List<Shipment> findByRouteFrom(UUID routeId, int fromSeq) {
            return stored.values().stream()
                    .filter(shipment -> shipment.routeId().equals(routeId)
                            && shipment.stopSeq() >= fromSeq)
                    .sorted(java.util.Comparator.comparingInt(Shipment::stopSeq)
                            .thenComparing(Shipment::orderId))
                    .toList();
        }

        @Override
        public void insert(Shipment shipment) {
            put(shipment);
        }

        @Override
        public void update(Shipment shipment) {
            updated.add(shipment.orderId());
            put(shipment);
        }
    }
}
