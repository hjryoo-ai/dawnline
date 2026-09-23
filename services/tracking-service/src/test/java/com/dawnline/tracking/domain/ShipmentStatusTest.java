package com.dawnline.tracking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 배송 상태의 전이표 (DESIGN.md §5.4).
 *
 * <p>집합을 도는 검사는 <strong>열거하지 않고 전체에서 뺀다</strong>(CLAUDE.md). 상태가 하나 늘면
 * 그 상태는 자동으로 검사 대상이 되고, 제외한 것이 왜 제외인지는 짝 테스트가 말한다.
 */
@DisplayName("ShipmentStatus 전이표")
class ShipmentStatusTest {

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"SCHEDULED", "OUT_FOR_DELIVERY", "ARRIVED"})
    void 종결_상태에서는_갈_곳이_없다(ShipmentStatus terminal) {
        assertThat(terminal.allowedTransitions()).isEmpty();
        assertThat(terminal.isTerminal()).isTrue();
    }

    @Test
    void 제외한_셋은_진행_중이라서_제외다() {
        // 위 검사에서 뺀 셋이 왜 제외인지를 여기서 말한다 — 없으면 다음 사람은
        // "검토했는데 제외" 와 "잊었다" 를 구별할 수 없다.
        for (ShipmentStatus inFlight
                : EnumSet.of(ShipmentStatus.SCHEDULED, ShipmentStatus.OUT_FOR_DELIVERY,
                        ShipmentStatus.ARRIVED)) {
            assertThat(inFlight.isTerminal())
                    .as("%s 는 아직 갈 곳이 있다", inFlight)
                    .isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "CANCELLED")
    void 취소를_뺀_모든_상태는_진행_축_위에_있다(ShipmentStatus onAxis) {
        assertThat(onAxis.progress()).isNotNegative();
    }

    @Test
    void 취소만_축_밖인_이유는_비교할_축이_없기_때문이다() {
        assertThat(ShipmentStatus.CANCELLED.progress()).isEqualTo(-1);
        // 축 밖이면 "이미 지나왔는가" 를 물을 수 없다 — 양방향 모두 거짓이다.
        assertThat(ShipmentStatus.CANCELLED.hasProgressedPast(ShipmentStatus.COMPLETED)).isFalse();
        assertThat(ShipmentStatus.COMPLETED.hasProgressedPast(ShipmentStatus.CANCELLED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ShipmentStatus.class)
    void 자기_자신으로는_전이하지_않는다(ShipmentStatus status) {
        // 중복은 processed_events 와 상태 머신이 앞단에서 거른다(불변규칙 2, §8.5).
        // 여기서 조용히 통과시키면 진짜 잘못된 전이까지 함께 숨는다.
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ShipmentStatus.class)
    void 진행_축_위의_전이는_언제나_앞으로만_간다(ShipmentStatus from) {
        for (ShipmentStatus to : from.allowedTransitions()) {
            if (to == ShipmentStatus.CANCELLED) {
                continue;   // 취소는 축 밖이라 앞뒤가 없다 — 아래 테스트가 따로 본다
            }
            assertThat(to.progress())
                    .as("%s → %s 는 뒤로 가는 전이다", from, to)
                    .isGreaterThan(from.progress());
        }
    }

    @Test
    void 취소는_도착_전까지만_받는다() {
        assertThat(ShipmentStatus.SCHEDULED.canTransitionTo(ShipmentStatus.CANCELLED)).isTrue();
        assertThat(ShipmentStatus.OUT_FOR_DELIVERY.canTransitionTo(ShipmentStatus.CANCELLED)).isTrue();
        // 도착 뒤의 취소는 dispatch 가 이미 거부하고 dawnline_cancel_too_late_total 로 센다
        // (§6.10 넷째 분기). 여기까지 오면 상류의 결함이다.
        assertThat(ShipmentStatus.ARRIVED.canTransitionTo(ShipmentStatus.CANCELLED)).isFalse();
    }

    @Test
    void 스캔_누락은_건너뜀으로_받아들인다() {
        // 도착 스캔을 빼먹고 완료만 찍는 일은 흔하고, 그때 물건은 실제로 전달됐다.
        assertThat(ShipmentStatus.SCHEDULED.canTransitionTo(ShipmentStatus.COMPLETED)).isTrue();
        assertThat(ShipmentStatus.SCHEDULED.canTransitionTo(ShipmentStatus.ARRIVED)).isTrue();
        assertThat(ShipmentStatus.OUT_FOR_DELIVERY.canTransitionTo(ShipmentStatus.FAILED)).isTrue();
    }

    @Test
    void 같은_지점도_이미_지나온_것이다() {
        // 같은 스캔이 두 번 와도 상태가 다시 움직이지 않는다 (§8.5).
        assertThat(ShipmentStatus.ARRIVED.hasProgressedPast(ShipmentStatus.ARRIVED)).isTrue();
        assertThat(ShipmentStatus.ARRIVED.hasProgressedPast(ShipmentStatus.OUT_FOR_DELIVERY)).isTrue();
        assertThat(ShipmentStatus.OUT_FOR_DELIVERY.hasProgressedPast(ShipmentStatus.ARRIVED)).isFalse();
    }

    @Test
    void 완료와_실패는_서로를_지나온_것으로_본다() {
        // 같은 단계(3)다. 완료 뒤에 오는 실패 스캔은 뒤늦게 온 것이지 새 사실이 아니다.
        assertThat(ShipmentStatus.COMPLETED.hasProgressedPast(ShipmentStatus.FAILED)).isTrue();
        assertThat(ShipmentStatus.FAILED.hasProgressedPast(ShipmentStatus.COMPLETED)).isTrue();
    }

    @Test
    void 스캔_종류는_모두_자기_목표_상태를_안다() {
        Set<ShipmentStatus> targets = EnumSet.noneOf(ShipmentStatus.class);
        for (ScanType type : ScanType.values()) {
            targets.add(type.targetStatus());
        }
        assertThat(targets)
                .as("스캔 넷이 만드는 상태는 SCHEDULED·CANCELLED 를 뺀 넷이다")
                .containsExactlyInAnyOrder(ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.ARRIVED,
                        ShipmentStatus.COMPLETED, ShipmentStatus.FAILED);
    }

    @Test
    void 캠프_출발만_발행하지_않는다() {
        // delivery.status.v1 의 status enum 은 셋이다 (ARRIVED/COMPLETED/FAILED).
        assertThat(ScanType.DEPARTED_CAMP.isDeliveryStatus()).isFalse();
        for (ScanType published
                : EnumSet.of(ScanType.ARRIVED, ScanType.COMPLETED, ScanType.FAILED)) {
            assertThat(published.isDeliveryStatus()).as("%s", published).isTrue();
        }
    }
}
