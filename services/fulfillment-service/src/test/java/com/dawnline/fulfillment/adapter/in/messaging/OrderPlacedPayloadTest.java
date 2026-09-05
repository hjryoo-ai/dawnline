package com.dawnline.fulfillment.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.fulfillment.application.port.in.PlacedOrderSnapshot;
import com.dawnline.fulfillment.domain.OrderToPlan;
import com.dawnline.fulfillment.domain.ServiceTier;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** {@code order.placed} 페이로드 → 스냅샷 → 판정 입력 변환. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OrderPlacedPayloadTest {

    private static final Instant CUTOFF = Instant.parse("2026-09-06T01:00:00Z");

    private static OrderPlacedPayload payload(String geohash7) {
        return new OrderPlacedPayload(UUID.randomUUID(), UUID.randomUUID(), "DAWN",
                new OrderPlacedPayload.Address("서울 강남구 테헤란로 1", "06236", 37.4979, 127.0276, geohash7),
                new OrderPlacedPayload.Window(CUTOFF, CUTOFF.plusSeconds(25200)),
                new OrderPlacedPayload.Parcel(1200, 8000, true, false),
                List.of(new OrderPlacedPayload.Item("SKU-00001", 2)),
                CUTOFF.minusSeconds(3600), CUTOFF);
    }

    @Test
    void 권역_키는_geohash7_의_앞_다섯_자다() {
        // 부록 C — geohash5 셀 하나가 권역 하나다.
        PlacedOrderSnapshot snapshot = payload("wydm7bc").toSnapshot();

        assertThat(snapshot.address().geohash5()).isEqualTo("wydm7");
    }

    @Test
    void 판정_입력에는_주소도_고객도_없다() {
        // 순수 함수의 입력 면적을 좁게 유지한다 — 좁을수록 무엇에 의존하는지가 서명에 드러난다.
        OrderToPlan order = payload("wydm7bc").toSnapshot().toOrderToPlan();

        assertThat(order.serviceTier()).isEqualTo(ServiceTier.DAWN);
        assertThat(order.requiresCold()).isTrue();
        assertThat(order.cutoffAt()).isEqualTo(CUTOFF);
        assertThat(order.lines()).singleElement()
                .satisfies(line -> {
                    assertThat(line.sku()).isEqualTo("SKU-00001");
                    assertThat(line.qty()).isEqualTo(2);
                });
    }

    @Test
    void 컷오프는_저장_정밀도로_잘린다() {
        // cutoffAt 은 웨이브의 자연키다. 나노초를 그대로 두면 PostgreSQL 이 잘라 저장하고,
        // 그 뒤 조회가 어긋나 모든 주문이 promiseRevised=true 로 나간다 — 거짓 약속 개정이다.
        // Linux 의 Instant.now() 는 나노초, macOS 는 마이크로초라 CI 에서만 드러났던 결함이다.
        Instant nanos = Instant.parse("2026-09-06T01:00:00Z").plusNanos(123_456);
        OrderPlacedPayload payload = new OrderPlacedPayload(UUID.randomUUID(), UUID.randomUUID(), "DAWN",
                new OrderPlacedPayload.Address("서울 강남구 테헤란로 1", "06236", 37.4979, 127.0276, "wydm7bc"),
                new OrderPlacedPayload.Window(nanos, nanos.plusSeconds(25200)),
                new OrderPlacedPayload.Parcel(1200, 8000, true, false),
                List.of(new OrderPlacedPayload.Item("SKU-00001", 2)),
                nanos.minusSeconds(3600), nanos);

        assertThat(payload.toSnapshot().cutoffAt())
                .isEqualTo(Instant.parse("2026-09-06T01:00:00Z").plusNanos(123_000));
    }

    @Test
    void geohash7_이_일곱_자가_아니면_거절한다() {
        // 계약이 CHAR(7) 이다. 짧으면 권역 키가 조용히 달라진다.
        assertThatThrownBy(() -> payload("wydm7").toSnapshot())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
