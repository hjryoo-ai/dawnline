package com.dawnline.tracking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * <p>시각 픽스처는 전부 <strong>주입된 시계에서 파생</strong>한다(CLAUDE.md). 완료 시각은
 * 단말이 말한 시각이 그대로 들어가는 자리라, 리터럴을 쓰면 「어디서 온 값인가」가 흐려진다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("기사 스캔")
class RecordScanServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T22:05:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final UUID ROUTE = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID SIBLING = UUID.randomUUID();
    private static final int SEQ = 3;

    private static final Instant ARRIVAL = NOW.plus(Duration.ofMinutes(20));
    private static final Instant PROMISED_END = NOW.plus(Duration.ofHours(2));

    private InMemoryShipments shipments;
    private RecordingEvents events;
    private MeterRegistry meters;
    private RecordScanService service;

    @BeforeEach
    void setUp() {
        shipments = new InMemoryShipments();
        events = new RecordingEvents();
        meters = new SimpleMeterRegistry();
        service = new RecordScanService(shipments, events, new TrackingMetrics(meters),
                new Ids(CLOCK, RandomGenerator.getDefault()));
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

        service.record(new ScanCommand(ROUTE, SEQ, ScanType.COMPLETED, scannedAt, null, null, null));

        assertThat(shipments.stored.get(ORDER).deliveredAt()).isEqualTo(scannedAt);
    }

    @Test
    void 좌표는_그대로_사건에_실린다() {
        shipments.put(scheduled(ORDER));

        service.record(new ScanCommand(ROUTE, SEQ, ScanType.ARRIVED, NOW, 37.4979, 127.0276, null));

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
                .hasMessageContaining(ROUTE.toString());
        assertThat(events.appended).isEmpty();
    }

    @Test
    void 사유는_FAILED_에만_붙는다() {
        shipments.put(scheduled(ORDER));

        assertThatThrownBy(() -> service.record(
                new ScanCommand(ROUTE, SEQ, ScanType.COMPLETED, NOW, null, null, "부재")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("failureReason");
    }

    @Test
    void 거절_메시지에_사유_본문을_싣지_않는다() {
        // 자유 텍스트라 개인정보가 섞일 수 있고, 오류 응답과 로그에 그대로 실린다 (§9.3).
        shipments.put(scheduled(ORDER));
        String reason = "우편함 비밀번호 1234, 옆집에 맡김";

        assertThatThrownBy(() -> service.record(
                new ScanCommand(ROUTE, SEQ, ScanType.ARRIVED, NOW, null, null, reason)))
                .isInstanceOf(ValidationException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("1234"));
    }

    @Test
    void 실패_사유는_사건에_실린다() {
        shipments.put(scheduled(ORDER));

        service.record(new ScanCommand(ROUTE, SEQ, ScanType.FAILED, NOW, null, null, "부재"));

        assertThat(events.appended).singleElement()
                .satisfies(event -> assertThat(event.failureReason()).isEqualTo("부재"));
    }

    @Test
    void stop_순번은_1_부터다() {
        // 경로 변수라 클라이언트가 만든 값이다. IllegalArgumentException 이면 500 으로 나간다.
        assertThatThrownBy(() -> new ScanCommand(ROUTE, 0, ScanType.ARRIVED, NOW, null, null, null))
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

    private static ScanCommand scan(ScanType type) {
        return new ScanCommand(ROUTE, SEQ, type, NOW, null, null, null);
    }

    private static Shipment scheduled(UUID orderId) {
        return Shipment.scheduled(orderId, ROUTE, SEQ, ARRIVAL, PROMISED_END);
    }

    private static Shipment cancelled(UUID orderId) {
        Shipment shipment = scheduled(orderId);
        shipment.cancel();
        return shipment;
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
        public List<Shipment> findByRouteAndStop(UUID routeId, int stopSeq) {
            return stored.values().stream()
                    .filter(shipment -> shipment.routeId().equals(routeId) && shipment.stopSeq() == stopSeq)
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
