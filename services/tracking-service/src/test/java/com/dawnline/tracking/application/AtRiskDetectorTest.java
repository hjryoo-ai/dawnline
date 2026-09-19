package com.dawnline.tracking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.tracking.application.EtaPropagator.Propagation;
import com.dawnline.tracking.application.port.out.AtRiskCooldown;
import com.dawnline.tracking.application.port.out.DeliveryEvents;
import com.dawnline.tracking.application.port.out.RouteRevisions;
import com.dawnline.tracking.domain.ScanType;
import com.dawnline.tracking.domain.Shipment;
import com.dawnline.tracking.domain.ShipmentStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

/**
 * 지연 위험 판정과 통지 (DESIGN.md §5.4).
 *
 * <p>여기서 고정하는 것은 <strong>비대칭</strong>이다 — 위험이 생기면 알리고, 사라지면 알리지
 * 않는다. 그리고 쿨다운이 무엇을 지키는지: 알림 수이지 정확성이 아니다 (ADR-046).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("AtRiskDetector — 위험은 사건이다")
class AtRiskDetectorTest {

    private static final UUID ROUTE = UUID.randomUUID();
    private static final UUID CAMP = UUID.randomUUID();
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC);
    private static final Duration MARGIN = Duration.ofMinutes(15);
    private static final Instant PROMISED_END = CLOCK.instant().plus(Duration.ofHours(2));

    private RecordingDelivery delivery;
    private CountingCooldown cooldown;
    private MeterRegistry meters;
    private AtRiskDetector detector;

    @BeforeEach
    void setUp() {
        delivery = new RecordingDelivery();
        cooldown = new CountingCooldown();
        meters = new SimpleMeterRegistry();
        detector = new AtRiskDetector(new FixedRevisions(), cooldown, delivery,
                new TrackingMetrics(meters), CLOCK, MARGIN);
    }

    @Test
    void 여유_안에_든_stop_이_있으면_알린다() {
        boolean published = detector.evaluate(ROUTE,
                propagation(Duration.ofMinutes(20), risky(2), safe(3)));

        assertThat(published).isTrue();
        assertThat(delivery.atRisk).singleElement().satisfies(sent -> {
            assertThat(sent.routeId()).isEqualTo(ROUTE);
            assertThat(sent.campId()).isEqualTo(CAMP);
            assertThat(sent.detectedAt())
                    .as("판정 시각은 주입된 시계에서 온다 — 스캔의 occurredAt 과 다를 수 있다")
                    .isEqualTo(CLOCK.instant());
            assertThat(sent.remaining()).extracting(Shipment::stopSeq)
                    .as("위험한 것만이 아니라 남은 전부다 — 재계획의 입력은 남은 구간이다")
                    .containsExactly(2, 3);
        });
        assertThat(meters.find(TrackingMetrics.AT_RISK).tag("camp", CAMP.toString()).counter())
                .isNotNull();
    }

    @Test
    void 위험이_없으면_알리지_않는다() {
        assertThat(detector.evaluate(ROUTE, propagation(Duration.ofMinutes(3), safe(2)))).isFalse();
        assertThat(delivery.atRisk).isEmpty();
        assertThat(cooldown.calls)
                .as("쿨다운을 먼저 소모하지 않는다 — 소모하면 진짜 위험이 왔을 때 창이 닫혀 있다")
                .isZero();
    }

    @Test
    void 위험이_사라져도_알리지_않는다() {
        // 비대칭이다. 이미 시작된 재계획을 취소할 방법이 없고, 해소된 ETA 는 ops 의 읽기
        // 모델이 그대로 보여 준다 (ADR-046).
        detector.evaluate(ROUTE, propagation(Duration.ofMinutes(20), risky(2)));
        delivery.atRisk.clear();

        assertThat(detector.evaluate(ROUTE, propagation(Duration.ofMinutes(-20), safe(2))))
                .isFalse();
        assertThat(delivery.atRisk).isEmpty();
    }

    @Test
    void 쿨다운_창이_열려_있으면_건너뛴다() {
        cooldown.open = false;

        assertThat(detector.evaluate(ROUTE, propagation(Duration.ofMinutes(20), risky(2))))
                .isFalse();
        assertThat(delivery.atRisk).isEmpty();
    }

    @Test
    void 종결된_배송은_남은_것이_아니다() {
        // Propagation.remaining 이 이미 걸러 주지만, 그 경계를 여기서도 못 박는다 —
        // 배송이 끝난 뒤에 늦었다는 사실은 위험이 아니라 결과다.
        assertThat(detector.evaluate(ROUTE, new Propagation(Duration.ofMinutes(30), List.of(),
                List.of()))).isFalse();
        assertThat(delivery.atRisk).isEmpty();
    }

    // --- 픽스처 --------------------------------------------------------------

    private static Propagation propagation(Duration deviation, Shipment... remaining) {
        return new Propagation(deviation, List.of(), List.of(remaining));
    }

    /** ETA 가 약속 끝 14분 전 — 여유(15분) 안이다. */
    private static Shipment risky(int seq) {
        return shipment(seq, PROMISED_END.minus(Duration.ofMinutes(14)));
    }

    /** ETA 가 약속 끝 40분 전. */
    private static Shipment safe(int seq) {
        return shipment(seq, PROMISED_END.minus(Duration.ofMinutes(40)));
    }

    private static Shipment shipment(int seq, Instant eta) {
        return Shipment.restore(UUID.randomUUID(), ROUTE, seq, ShipmentStatus.OUT_FOR_DELIVERY,
                eta, eta, PROMISED_END, null, 0L);
    }

    private static final class FixedRevisions implements RouteRevisions {

        @Override
        public boolean claim(UUID routeId, int revision, UUID campId, Instant plannedDeparture,
                Instant appliedAt) {
            throw new UnsupportedOperationException("판정은 선점하지 않습니다");
        }

        @Override
        public Optional<RoutePlanned> find(UUID routeId) {
            return Optional.of(new RoutePlanned(CAMP, CLOCK.instant()));
        }
    }

    private static final class CountingCooldown implements AtRiskCooldown {

        private boolean open = true;
        private int calls;

        @Override
        public boolean tryStart(UUID routeId) {
            calls++;
            return open;
        }
    }

    private record Sent(UUID routeId, UUID campId, Instant detectedAt, Duration deviation,
            List<Shipment> remaining) {
    }

    private static final class RecordingDelivery implements DeliveryEvents {

        private final List<Sent> atRisk = new ArrayList<>();

        @Override
        public void deliveryStatus(UUID routeId, int stopSeq, List<UUID> orderIds, ScanType type,
                Instant occurredAt, @Nullable String failureReason) {
            throw new UnsupportedOperationException("판정은 delivery.status 를 내지 않습니다");
        }

        @Override
        public void deliveryAtRisk(UUID routeId, UUID campId, Instant detectedAt,
                Duration deviation, List<Shipment> remaining, Duration margin) {
            atRisk.add(new Sent(routeId, campId, detectedAt, deviation, List.copyOf(remaining)));
        }
    }
}
