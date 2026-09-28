package com.dawnline.tracking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.tracking.application.EtaPropagator.Propagation;
import com.dawnline.tracking.application.port.out.RouteRevisions;
import com.dawnline.tracking.application.port.out.ShipmentRepository;
import com.dawnline.tracking.domain.ScanType;
import com.dawnline.tracking.domain.Shipment;
import com.dawnline.tracking.domain.ShipmentStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 편차 전파 (DESIGN.md §5.4 ETA 재계산).
 *
 * <p>여기서 고정하는 것은 <strong>경계</strong>다: 편차는 이 클래스가 재서 라우트 행에 한 번 적고(ADR-070), ETA 는 배송이
 * 계획 + 편차로 계산한다. 배송은 쓰지 않는다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("EtaPropagator — 편차는 라우트의 것이다")
class EtaPropagatorTest {

    private static final UUID ROUTE = UUID.randomUUID();
    private static final UUID CAMP = UUID.randomUUID();

    /** 시각 리터럴을 쓰지 않는다 — 전부 이 기준에서 파생한다 (CLAUDE.md). */
    private static final Instant BASE = Instant.parse("2026-09-19T00:00:00Z");
    private static final Instant DEPARTURE = BASE;
    private static final Duration LATE = Duration.ofMinutes(18);

    private InMemoryShipments shipments;
    private FixedRevisions revisions;
    private EtaPropagator propagator;

    @BeforeEach
    void setUp() {
        shipments = new InMemoryShipments();
        revisions = new FixedRevisions();
        propagator = new EtaPropagator(shipments, revisions);
    }

    @Test
    void 편차는_라우트_행에_한_번_적고_배송은_쓰지_않는다() {
        // 뒤 stop 마다 eta_at 을 옮겨 적던 것이 라우트당 O(n²) 였다(ADR-070 결정 2 — 30-stop 라우트에 990).
        put(1, ShipmentStatus.ARRIVED);
        put(2, ShipmentStatus.SCHEDULED);
        put(3, ShipmentStatus.SCHEDULED);

        Propagation result = propagator.propagate(ROUTE, ScanType.ARRIVED, 1,
                arrivalOf(1).plus(LATE));

        assertThat(result.deviation()).isEqualTo(LATE);
        assertThat(revisions.recorded).containsExactly(Map.entry(ROUTE, LATE));
        assertThat(shipments.updated).as("배송은 계획만 들고 있다 — ETA 는 읽는 자리가 계산한다").isEmpty();
        assertThat(result.remaining()).extracting(Shipment::stopSeq).containsExactly(1, 2, 3);
        assertThat(result.remaining().get(2).etaWith(result.deviation())).contains(arrivalOf(3).plus(LATE));
    }

    @Test
    void 캠프_출발의_기준은_계획_출발이다() {
        // 늦은 출발은 첫 도착 스캔 전에 이미 아는 위험이다. 기준은 route_revisions 의 planned_departure 이고,
        // 남은 배송은 1번 stop 부터다.
        put(1, ShipmentStatus.OUT_FOR_DELIVERY);
        put(2, ShipmentStatus.OUT_FOR_DELIVERY);

        Propagation result = propagator.propagate(ROUTE, ScanType.DEPARTED_CAMP, 1,
                DEPARTURE.plus(LATE));

        assertThat(result.deviation()).isEqualTo(LATE);
        assertThat(revisions.recorded).containsExactly(Map.entry(ROUTE, LATE));
        assertThat(result.remaining()).extracting(Shipment::stopSeq).containsExactly(1, 2);
    }

    @Test
    void 종결된_배송은_남은_목록에_없다() {
        put(1, ShipmentStatus.COMPLETED);
        put(2, ShipmentStatus.CANCELLED);
        put(3, ShipmentStatus.SCHEDULED);

        Propagation result = propagator.propagate(ROUTE, ScanType.DEPARTED_CAMP, 1,
                DEPARTURE.plus(LATE));

        assertThat(result.remaining()).extracting(Shipment::stopSeq)
                .as("at-risk 판정의 대상은 아직 끝나지 않은 배송이다")
                .containsExactly(3);
    }

    @Test
    void 일찍_도착하면_음수로_적는다() {
        // 「늦은 것만 민다」로 적으면 앞서 가는 라우트의 ETA 가 낡은 채로 남고 ops 가 그 값을 읽는다.
        put(1, ShipmentStatus.ARRIVED);
        put(2, ShipmentStatus.SCHEDULED);

        Propagation result = propagator.propagate(ROUTE, ScanType.ARRIVED, 1,
                arrivalOf(1).minus(Duration.ofMinutes(7)));

        assertThat(result.deviation()).isNegative();
        assertThat(revisions.recorded).containsExactly(Map.entry(ROUTE, Duration.ofMinutes(-7)));
    }

    @Test
    void 배송이_없는_라우트는_적을_것이_없다() {
        Propagation result = propagator.propagate(ROUTE, ScanType.ARRIVED, 1, BASE);

        assertThat(result.deviation()).isZero();
        assertThat(result.remaining()).isEmpty();
        assertThat(revisions.recorded).isEmpty();
    }

    @Test
    void 스캔이_난_stop_이_라우트에_없으면_멈춘다() {
        // 부르는 쪽이 방금 그 stop 의 배송을 읽었다. 여기까지 오면 같은 트랜잭션 안에서
        // 사라졌다는 뜻이고, 조용히 0 으로 두면 편차가 통째로 사라진다.
        put(2, ShipmentStatus.SCHEDULED);

        assertThatThrownBy(() -> propagator.propagate(ROUTE, ScanType.ARRIVED, 1, BASE))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("seq=1");
    }

    // --- 픽스처 --------------------------------------------------------------

    /** stop 순번 n 의 계획 도착은 출발 + 10n 분이다. */
    private static Instant arrivalOf(int seq) {
        return DEPARTURE.plus(Duration.ofMinutes(10L * seq));
    }

    private static UUID key(int seq) {
        return UUID.nameUUIDFromBytes(("stop-" + seq).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void put(int seq, ShipmentStatus status) {
        Instant arrival = arrivalOf(seq);
        shipments.put(Shipment.restore(key(seq), ROUTE, seq, status, arrival,
                arrival.plus(Duration.ofHours(1)),
                status == ShipmentStatus.COMPLETED ? arrival : null, 0L));
    }

    /** 계획 출발 시각을 말하고, 적은 편차를 기억한다. */
    private static final class FixedRevisions implements RouteRevisions {

        private final List<Map.Entry<UUID, Duration>> recorded = new ArrayList<>();

        @Override
        public boolean claim(UUID routeId, int revision, UUID campId, Instant plannedDeparture,
                Instant appliedAt) {
            throw new UnsupportedOperationException("전파는 선점하지 않습니다");
        }

        @Override
        public Optional<RoutePlanned> find(UUID routeId) {
            return Optional.of(new RoutePlanned(CAMP, 1, DEPARTURE));
        }

        @Override
        public void lockForWrite(Collection<UUID> routeIds) {
            throw new UnsupportedOperationException("잡는 것은 부르는 쪽이다 — 전파는 잡힌 행에 적는다");
        }

        @Override
        public void recordDeviation(UUID routeId, Duration deviation) {
            recorded.add(Map.entry(routeId, deviation));
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
                    .sorted(Comparator.comparingInt(Shipment::stopSeq))
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
