package com.dawnline.tracking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Ids;
import com.dawnline.common.error.IllegalStateTransitionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 배송 애그리거트 (DESIGN.md §5.4).
 *
 * <p>시각 픽스처는 전부 주입된 시계에서 파생한다(CLAUDE.md).
 */
@DisplayName("Shipment")
class ShipmentTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-19T10:00:00Z"), ZoneOffset.UTC);

    private static final Instant PLANNED = CLOCK.instant().plus(Duration.ofHours(2));
    private static final Instant PROMISED_END = CLOCK.instant().plus(Duration.ofHours(4));

    private static Shipment scheduled() {
        return Shipment.scheduled(Ids.newId(), Ids.newId(), 3, PLANNED, PROMISED_END);
    }

    @Test
    void 새_배송은_SCHEDULED_이고_ETA_가_계획_도착_시각이다() {
        Shipment shipment = scheduled();

        assertThat(shipment.status()).isEqualTo(ShipmentStatus.SCHEDULED);
        assertThat(shipment.etaAt()).isEqualTo(PLANNED);
        assertThat(shipment.deliveredAt()).isNull();
        assertThat(shipment.version()).isZero();
    }

    @Test
    void stop_순번은_1_이상이다() {
        assertThatThrownBy(() -> Shipment.scheduled(Ids.newId(), Ids.newId(), 0, PLANNED, PROMISED_END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stopSeq");
    }

    // --- 스캔 -------------------------------------------------------------------

    @Test
    void 스캔은_상태를_옮긴다() {
        Shipment shipment = scheduled();

        assertThat(shipment.recordScan(ScanType.DEPARTED_CAMP, CLOCK.instant()))
                .isEqualTo(ScanOutcome.APPLIED);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void 완료_스캔은_완료_시각을_남긴다() {
        Shipment shipment = scheduled();
        Instant deliveredAt = CLOCK.instant().plus(Duration.ofHours(1));

        shipment.recordScan(ScanType.COMPLETED, deliveredAt);

        assertThat(shipment.status()).isEqualTo(ShipmentStatus.COMPLETED);
        assertThat(shipment.deliveredAt())
                .as("기사 단말이 말한 사건 시각이다 — 우리가 처리한 시각이 아니다(정시율의 입력)")
                .isEqualTo(deliveredAt);
    }

    @Test
    void 도착_스캔을_빼먹어도_완료는_완료다() {
        Shipment shipment = scheduled();

        assertThat(shipment.recordScan(ScanType.COMPLETED, CLOCK.instant()))
                .isEqualTo(ScanOutcome.APPLIED);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.COMPLETED);
    }

    @Test
    void 역행_스캔은_무시한다() {
        Shipment shipment = scheduled();
        shipment.recordScan(ScanType.ARRIVED, CLOCK.instant());

        assertThat(shipment.recordScan(ScanType.DEPARTED_CAMP, CLOCK.instant()))
                .isEqualTo(ScanOutcome.STALE);
        assertThat(shipment.status())
                .as("사실은 이미 일어났고, 순서가 다른 것은 우리가 알게 된 순서일 뿐이다")
                .isEqualTo(ShipmentStatus.ARRIVED);
    }

    @Test
    void 같은_스캔이_두_번_와도_한_번만_움직인다() {
        Shipment shipment = scheduled();
        Instant first = CLOCK.instant();
        shipment.recordScan(ScanType.COMPLETED, first);

        assertThat(shipment.recordScan(ScanType.COMPLETED, first.plus(Duration.ofMinutes(5))))
                .isEqualTo(ScanOutcome.STALE);
        assertThat(shipment.deliveredAt())
                .as("두 번째 스캔이 완료 시각을 덮으면 정시율이 재배달 시각으로 바뀐다")
                .isEqualTo(first);
    }

    @ParameterizedTest
    @EnumSource(ScanType.class)
    void 취소_뒤의_스캔은_종류를_가리지_않고_세어_무시한다(ScanType type) {
        Shipment shipment = scheduled();
        assertThat(shipment.cancel()).isTrue();

        assertThat(shipment.recordScan(type, CLOCK.instant()))
                .as("기사가 취소를 못 받고 배송한 경우다 — dawnline_scan_after_cancel_total")
                .isEqualTo(ScanOutcome.AFTER_CANCEL);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    void 취소_뒤의_완료_스캔이_예외가_되지_않는다() {
        // 진행 축을 먼저 물으면 CANCELLED 가 축 밖이라 전이 실패로 터지고, 기사 단말이 500 을
        // 받는다. 그것은 기사가 고칠 수 있는 문제가 아니다.
        Shipment shipment = scheduled();
        shipment.cancel();

        assertThat(shipment.recordScan(ScanType.COMPLETED, CLOCK.instant()))
                .isEqualTo(ScanOutcome.AFTER_CANCEL);
        assertThat(shipment.deliveredAt()).isNull();
    }

    @Test
    void 완료_뒤의_실패_스캔은_무시한다() {
        Shipment shipment = scheduled();
        shipment.recordScan(ScanType.COMPLETED, CLOCK.instant());

        assertThat(shipment.recordScan(ScanType.FAILED, CLOCK.instant().plus(Duration.ofMinutes(1))))
                .isEqualTo(ScanOutcome.STALE);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.COMPLETED);
    }

    @Test
    void 스캔은_ETA_를_건드리지_않는다() {
        // 편차 전파는 이 stop 하나가 아니라 뒤따르는 stop 들의 문제다 (Phase 5-1b).
        Shipment shipment = scheduled();
        shipment.recordScan(ScanType.ARRIVED, CLOCK.instant().plus(Duration.ofHours(3)));

        assertThat(shipment.etaAt()).isEqualTo(PLANNED);
    }

    // --- 개정 -------------------------------------------------------------------

    @Test
    void 개정은_라우트와_순번과_시각을_갱신한다() {
        Shipment shipment = scheduled();
        UUID movedTo = Ids.newId();
        Instant newArrival = PLANNED.plus(Duration.ofMinutes(40));
        Instant newPromise = PROMISED_END.plus(Duration.ofMinutes(40));

        assertThat(shipment.applyRevision(movedTo, 7, newArrival, newPromise)).isTrue();

        assertThat(shipment.routeId()).as("relocate 가 stop 을 옮길 수 있다 (§6.8)").isEqualTo(movedTo);
        assertThat(shipment.stopSeq()).isEqualTo(7);
        assertThat(shipment.plannedArrival()).isEqualTo(newArrival);
        assertThat(shipment.promisedEnd()).isEqualTo(newPromise);
        assertThat(shipment.etaAt())
                .as("개정은 새 계획이고, 그 이전의 편차는 이미 반영돼 있다")
                .isEqualTo(newArrival);
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"SCHEDULED", "OUT_FOR_DELIVERY", "ARRIVED"})
    void 개정은_종결_상태를_되돌리지_않는다(ShipmentStatus terminal) {
        Shipment shipment = Shipment.restore(Ids.newId(), Ids.newId(), 3, terminal,
                PLANNED, PLANNED, PROMISED_END, null, 4L);
        UUID before = shipment.routeId();

        assertThat(shipment.applyRevision(Ids.newId(), 9, PLANNED.plus(Duration.ofHours(1)),
                PROMISED_END.plus(Duration.ofHours(1)))).isFalse();

        assertThat(shipment.status()).isEqualTo(terminal);
        assertThat(shipment.routeId()).isEqualTo(before);
        assertThat(shipment.plannedArrival()).isEqualTo(PLANNED);
    }

    @Test
    void 제외한_셋은_진행_중이라_개정을_받는다() {
        // 위 검사에서 뺀 셋이 왜 제외인지 — Phase 5-5 전에는 dispatch 가 진행을 모르므로
        // 이 구분이 tracking 쪽의 유일한 방어선이다.
        for (ShipmentStatus inFlight : new ShipmentStatus[] {ShipmentStatus.SCHEDULED,
                ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.ARRIVED}) {
            Shipment shipment = Shipment.restore(Ids.newId(), Ids.newId(), 3, inFlight,
                    PLANNED, PLANNED, PROMISED_END, null, 1L);
            assertThat(shipment.applyRevision(Ids.newId(), 9, PLANNED, PROMISED_END))
                    .as("%s", inFlight)
                    .isTrue();
        }
    }

    // --- 취소 -------------------------------------------------------------------

    @Test
    void 취소는_한_번만_적용된다() {
        Shipment shipment = scheduled();

        assertThat(shipment.cancel()).isTrue();
        assertThat(shipment.cancel())
                .as("취소된 stop 은 페이로드에서 지우지 않으므로 같은 취소가 개정마다 실려 온다")
                .isFalse();
    }

    @Test
    void 배송이_끝난_뒤의_취소는_상태를_바꾸지_않는다() {
        Shipment shipment = scheduled();
        shipment.recordScan(ScanType.COMPLETED, CLOCK.instant());

        assertThat(shipment.cancel()).isFalse();
        assertThat(shipment.status())
                .as("같은 페이로드의 나머지 stop 까지 롤백시키지 않는다 — 세는 것은 상류다")
                .isEqualTo(ShipmentStatus.COMPLETED);
    }

    // --- ETA 와 위험 (Phase 5-1b) ----------------------------------------------

    @Test
    void ETA_는_받은_값으로_옮겨진다() {
        // 얼마나 옮기는지는 애그리거트가 정하지 않는다 — 편차는 라우트의 성질이고, 여기 오는
        // 것은 결과값 하나다 (EtaPropagator).
        Shipment shipment = scheduled();
        Instant moved = PLANNED.plus(Duration.ofMinutes(25));

        assertThat(shipment.projectEta(moved)).isTrue();
        assertThat(shipment.etaAt()).isEqualTo(moved);
        assertThat(shipment.plannedArrival())
                .as("계획은 계획대로 남는다 — 다음 편차도 여기서 잰다")
                .isEqualTo(PLANNED);
    }

    @Test
    void 같은_ETA_로는_옮기지_않는다() {
        Shipment shipment = scheduled();

        assertThat(shipment.projectEta(PLANNED))
                .as("편차 0 에 UPDATE 와 낙관적 락 충돌을 만들지 않는다")
                .isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
    void 종결된_배송의_ETA_는_움직이지_않는다(ShipmentStatus terminal) {
        Shipment shipment = Shipment.restore(Ids.newId(), Ids.newId(), 1, terminal,
                PLANNED, PLANNED, PROMISED_END, null, 0L);

        assertThat(shipment.projectEta(PLANNED.plus(Duration.ofHours(1)))).isFalse();
        assertThat(shipment.etaAt()).isEqualTo(PLANNED);
    }

    @Test
    void 약속_끝에서_여유_안에_들면_위험이다() {
        // §5.4 — eta > promised_end − 15분.
        Shipment shipment = scheduled();
        shipment.projectEta(PROMISED_END.minus(Duration.ofMinutes(14)));

        assertThat(shipment.isAtRisk(Duration.ofMinutes(15))).isTrue();
    }

    @Test
    void 여유_경계_위는_위험이_아니다() {
        // 경계는 <em>초과</em>다. 같은 값이 위험이면 정확히 15분 남은 라우트가 매번 알림을 낸다.
        Shipment shipment = scheduled();
        shipment.projectEta(PROMISED_END.minus(Duration.ofMinutes(15)));

        assertThat(shipment.isAtRisk(Duration.ofMinutes(15))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
    void 종결된_배송은_위험하지_않다(ShipmentStatus terminal) {
        // 늦게 끝난 것은 사실이지만 「위험」이 아니다 — 재계획으로 되돌릴 것이 없다.
        Shipment shipment = Shipment.restore(Ids.newId(), Ids.newId(), 1, terminal,
                PLANNED, PROMISED_END.plus(Duration.ofHours(1)), PROMISED_END, null, 0L);

        assertThat(shipment.isAtRisk(Duration.ofMinutes(15))).isFalse();
    }

    @Test
    void 도착한_뒤의_취소는_소리를_낸다() {
        // dispatch 가 이미 거부했어야 하는 건이다(§6.10 넷째 분기). 여기까지 왔다면 상류의
        // 결함이고, 조용히 넘기면 그 결함이 보이지 않는다.
        Shipment shipment = scheduled();
        shipment.recordScan(ScanType.ARRIVED, CLOCK.instant());

        assertThatThrownBy(shipment::cancel)
                .isInstanceOf(IllegalStateTransitionException.class);
    }
}
