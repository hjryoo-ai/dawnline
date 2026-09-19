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
 * <p>여기서 고정하는 것은 <strong>경계</strong>다: 얼마나 옮기는지는 이 클래스가 정하고,
 * 옮길지 말지는 애그리거트가 정한다. 둘을 한쪽으로 모으면 「어디서 움직이는가」의 답이
 * 둘이 된다.
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
    private EtaPropagator propagator;

    @BeforeEach
    void setUp() {
        shipments = new InMemoryShipments();
        propagator = new EtaPropagator(shipments, new FixedRevisions());
    }

    @Test
    void 뒤따르는_stop_만_밀린다() {
        put(1, ShipmentStatus.ARRIVED);
        put(2, ShipmentStatus.SCHEDULED);
        put(3, ShipmentStatus.SCHEDULED);

        Propagation result = propagator.propagate(ROUTE, ScanType.ARRIVED, 1,
                arrivalOf(1).plus(LATE));

        assertThat(result.deviation()).isEqualTo(LATE);
        assertThat(result.moved()).extracting(Shipment::stopSeq).containsExactly(2, 3);
        assertThat(shipments.stored.get(key(1)).etaAt())
                .as("스캔이 난 stop 의 ETA 는 건드리지 않는다 — 실제 시각이 이미 사실이다")
                .isEqualTo(arrivalOf(1));
        assertThat(shipments.stored.get(key(3)).etaAt()).isEqualTo(arrivalOf(3).plus(LATE));
    }

    @Test
    void 캠프_출발은_라우트_전체를_민다() {
        // 늦은 출발은 첫 도착 스캔 전에 이미 아는 위험이다. 기준은 route_revisions 의
        // planned_departure 이고, 전파 대상은 1번 stop 부터다.
        put(1, ShipmentStatus.OUT_FOR_DELIVERY);
        put(2, ShipmentStatus.OUT_FOR_DELIVERY);

        Propagation result = propagator.propagate(ROUTE, ScanType.DEPARTED_CAMP, 1,
                DEPARTURE.plus(LATE));

        assertThat(result.deviation()).isEqualTo(LATE);
        assertThat(result.moved()).extracting(Shipment::stopSeq).containsExactly(1, 2);
        assertThat(shipments.stored.get(key(1)).etaAt()).isEqualTo(arrivalOf(1).plus(LATE));
    }

    @Test
    void 종결된_배송은_밀리지_않는다() {
        put(1, ShipmentStatus.COMPLETED);
        put(2, ShipmentStatus.CANCELLED);
        put(3, ShipmentStatus.SCHEDULED);

        Propagation result = propagator.propagate(ROUTE, ScanType.DEPARTED_CAMP, 1,
                DEPARTURE.plus(LATE));

        assertThat(result.moved()).extracting(Shipment::stopSeq)
                .as("COMPLETED·CANCELLED 의 도착 예정 시각을 미루는 일은 아무 물음에도 답하지 않는다")
                .containsExactly(3);
        assertThat(result.remaining()).extracting(Shipment::stopSeq)
                .as("at-risk 판정의 대상은 아직 끝나지 않은 배송이다")
                .containsExactly(3);
    }

    @Test
    void 일찍_도착하면_음수로_당겨진다() {
        // 「늦은 것만 민다」로 적으면 앞서 가는 라우트의 ETA 가 낡은 채로 남고 ops 가 그 값을 읽는다.
        put(1, ShipmentStatus.ARRIVED);
        put(2, ShipmentStatus.SCHEDULED);

        Propagation result = propagator.propagate(ROUTE, ScanType.ARRIVED, 1,
                arrivalOf(1).minus(Duration.ofMinutes(7)));

        assertThat(result.deviation()).isNegative();
        assertThat(shipments.stored.get(key(2)).etaAt())
                .isEqualTo(arrivalOf(2).minus(Duration.ofMinutes(7)));
    }

    @Test
    void 편차가_0_이면_아무것도_쓰지_않는다() {
        put(1, ShipmentStatus.ARRIVED);
        put(2, ShipmentStatus.SCHEDULED);

        Propagation result = propagator.propagate(ROUTE, ScanType.ARRIVED, 1, arrivalOf(1));

        assertThat(result.moved()).isEmpty();
        assertThat(shipments.updated)
                .as("값이 같으면 UPDATE 도 낙관적 락 충돌도 만들지 않는다")
                .isEmpty();
    }

    @Test
    void 배송이_없는_라우트는_전파할_것이_없다() {
        Propagation result = propagator.propagate(ROUTE, ScanType.ARRIVED, 1, BASE);

        assertThat(result.deviation()).isZero();
        assertThat(result.moved()).isEmpty();
        assertThat(result.remaining()).isEmpty();
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
        shipments.put(Shipment.restore(key(seq), ROUTE, seq, status, arrival, arrival,
                arrival.plus(Duration.ofHours(1)),
                status == ShipmentStatus.COMPLETED ? arrival : null, 0L));
    }

    /** 계획 출발 시각만 말한다. */
    private static final class FixedRevisions implements RouteRevisions {

        @Override
        public boolean claim(UUID routeId, int revision, UUID campId, Instant plannedDeparture,
                Instant appliedAt) {
            throw new UnsupportedOperationException("전파는 선점하지 않습니다");
        }

        @Override
        public Optional<RoutePlanned> find(UUID routeId) {
            return Optional.of(new RoutePlanned(CAMP, DEPARTURE));
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
            throw new UnsupportedOperationException("전파는 stop 하나만 보지 않습니다");
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
