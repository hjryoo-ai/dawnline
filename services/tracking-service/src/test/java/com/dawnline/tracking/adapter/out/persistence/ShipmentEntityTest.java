package com.dawnline.tracking.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.tracking.domain.ScanType;
import com.dawnline.tracking.domain.Shipment;
import com.dawnline.tracking.domain.ShipmentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 도메인 ↔ {@code shipments} 행 변환 (ADR-007).
 *
 * <p>왕복이 손실 없이 도는지를 여기서 본다. 통합 테스트는 <em>결과</em>를 보므로, 한 칸이
 * 반대 방향에서만 빠져 있으면(예: {@code apply} 가 {@code delivered_at} 을 안 옮긴다) 그 결함은
 * "완료했는데 완료 시각이 비어 있다" 로 한참 뒤에 나타난다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ShipmentEntity 매핑")
class ShipmentEntityTest {

    private static final Instant NOW = Instant.parse("2026-09-19T21:10:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Instant LATER = NOW.plus(Duration.ofMinutes(5));

    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID ROUTE = UUID.randomUUID();
    private static final Instant ARRIVAL = CLOCK.instant().plus(Duration.ofMinutes(30));
    private static final Instant PROMISED_END = CLOCK.instant().plus(Duration.ofHours(3));

    @Test
    void 새_배송을_그대로_옮긴다() {
        Shipment shipment = Shipment.scheduled(ORDER, ROUTE, 7, ARRIVAL, PROMISED_END);

        Shipment roundTrip = ShipmentEntity.from(shipment, NOW).toDomain();

        assertThat(roundTrip.orderId()).isEqualTo(ORDER);
        assertThat(roundTrip.routeId()).isEqualTo(ROUTE);
        assertThat(roundTrip.stopSeq()).isEqualTo(7);
        assertThat(roundTrip.status()).isEqualTo(ShipmentStatus.SCHEDULED);
        assertThat(roundTrip.plannedArrival()).isEqualTo(ARRIVAL);
        assertThat(roundTrip.etaAt()).isEqualTo(ARRIVAL);
        assertThat(roundTrip.promisedEnd()).isEqualTo(PROMISED_END);
        assertThat(roundTrip.deliveredAt()).isNull();
    }

    @Test
    void 완료_시각도_함께_간다() {
        Shipment shipment = Shipment.scheduled(ORDER, ROUTE, 1, ARRIVAL, PROMISED_END);
        shipment.recordScan(ScanType.COMPLETED, CLOCK.instant());

        Shipment roundTrip = ShipmentEntity.from(shipment, NOW).toDomain();

        assertThat(roundTrip.status()).isEqualTo(ShipmentStatus.COMPLETED);
        assertThat(roundTrip.deliveredAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void 갱신은_PK_를_빼고_전부_덮는다() {
        ShipmentEntity entity =
                ShipmentEntity.from(Shipment.scheduled(ORDER, ROUTE, 1, ARRIVAL, PROMISED_END), NOW);
        UUID movedRoute = UUID.randomUUID();
        Shipment moved = Shipment.scheduled(ORDER, movedRoute, 12,
                ARRIVAL.plus(Duration.ofMinutes(20)), PROMISED_END.plus(Duration.ofMinutes(20)));

        assertThat(entity.apply(moved, LATER)).isTrue();

        Shipment result = entity.toDomain();
        assertThat(result.orderId()).isEqualTo(ORDER);
        assertThat(result.routeId()).isEqualTo(movedRoute);
        assertThat(result.stopSeq()).isEqualTo(12);
        assertThat(result.plannedArrival()).isEqualTo(ARRIVAL.plus(Duration.ofMinutes(20)));
        assertThat(result.promisedEnd()).isEqualTo(PROMISED_END.plus(Duration.ofMinutes(20)));
        assertThat(entity.updatedAt()).as("값이 바뀐 쓰기는 나이를 옮긴다").isEqualTo(LATER);
    }

    @Test
    void 새_행의_나이는_넣은_시각이다() {
        assertThat(ShipmentEntity.from(Shipment.scheduled(ORDER, ROUTE, 1, ARRIVAL, PROMISED_END), NOW).updatedAt())
                .isEqualTo(NOW);
    }

    @Test
    void 바뀐_값이_없는_반영은_나이를_옮기지_않는다() {
        // 보존의 나이는 「마지막 사건」이다(ADR-058 결정 4). 같은 배송을 다시 저장하는 것은 사건이 아니고,
        // 그때 시각을 옮기면 종결된 배송이 저장될 때마다 30일이 다시 시작된다.
        Shipment same = Shipment.scheduled(ORDER, ROUTE, 1, ARRIVAL, PROMISED_END);
        ShipmentEntity entity = ShipmentEntity.from(same, NOW);

        assertThat(entity.apply(Shipment.scheduled(ORDER, ROUTE, 1, ARRIVAL, PROMISED_END), LATER)).isFalse();

        assertThat(entity.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void 칸_하나만_바뀌어도_나이를_옮긴다() {
        // sameAs 가 칸 하나를 빠뜨리면 그 칸만 바뀐 쓰기가 나이를 못 옮긴다 — 칸마다 한 번씩 본다.
        Shipment base = Shipment.scheduled(ORDER, ROUTE, 1, ARRIVAL, PROMISED_END);
        ShipmentStatus scheduled = ShipmentStatus.SCHEDULED;
        List<Shipment> oneChange = List.of(
                Shipment.restore(ORDER, UUID.randomUUID(), 1, scheduled, ARRIVAL, ARRIVAL, PROMISED_END, null, 0),
                Shipment.restore(ORDER, ROUTE, 2, scheduled, ARRIVAL, ARRIVAL, PROMISED_END, null, 0),
                Shipment.restore(ORDER, ROUTE, 1, ShipmentStatus.OUT_FOR_DELIVERY, ARRIVAL, ARRIVAL, PROMISED_END, null, 0),
                Shipment.restore(ORDER, ROUTE, 1, scheduled, ARRIVAL.plusSeconds(1), ARRIVAL, PROMISED_END, null, 0),
                Shipment.restore(ORDER, ROUTE, 1, scheduled, ARRIVAL, ARRIVAL.plusSeconds(1), PROMISED_END, null, 0),
                Shipment.restore(ORDER, ROUTE, 1, scheduled, ARRIVAL, ARRIVAL, PROMISED_END.plusSeconds(1), null, 0),
                Shipment.restore(ORDER, ROUTE, 1, scheduled, ARRIVAL, ARRIVAL, PROMISED_END, NOW, 0));

        for (Shipment changed : oneChange) {
            ShipmentEntity entity = ShipmentEntity.from(base, NOW);
            assertThat(entity.apply(changed, LATER)).as("%s", changed).isTrue();
            assertThat(entity.updatedAt()).isEqualTo(LATER);
        }
    }

    @Test
    void 다른_주문의_배송은_덮지_않는다() {
        // PK 가 order_id 라 조용히 덮으면 두 주문의 배송이 한 행으로 합쳐진다.
        ShipmentEntity entity =
                ShipmentEntity.from(Shipment.scheduled(ORDER, ROUTE, 1, ARRIVAL, PROMISED_END), NOW);
        Shipment other = Shipment.scheduled(UUID.randomUUID(), ROUTE, 1, ARRIVAL, PROMISED_END);

        assertThatThrownBy(() -> entity.apply(other, LATER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ORDER.toString());
    }

    @Test
    void stop_순번은_SMALLINT_상한까지_살아남는다() {
        // 계약의 seq 상한이 32767 이다(route_stops.seq 가 SMALLINT). short 로 좁히는 자리가
        // 여기뿐이라, 넘치면 음수가 되어 CHECK (stop_seq >= 1) 에서 터진다.
        Shipment shipment = Shipment.scheduled(ORDER, ROUTE, Short.MAX_VALUE, ARRIVAL, PROMISED_END);

        assertThat(ShipmentEntity.from(shipment, NOW).toDomain().stopSeq()).isEqualTo(Short.MAX_VALUE);
    }
}
