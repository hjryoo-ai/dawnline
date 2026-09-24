package com.dawnline.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.FulfillmentOrder;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.WaveCloseCause;
import com.dawnline.fulfillment.domain.WaveStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 마감 본문 — 스케줄러와 운영자가 같은 코드를 부른다 (§5.2, ADR-054 결정 5).
 *
 * <p>호출자가 누구든 같은 일을 한다는 것이 이 클래스의 존재 이유라, 원인 둘로 같은 결과가 나오는지를 본다.
 * 락이 실제로 무엇을 막는지는 {@code WaveLifecycleIT} 가 실물 DB 로 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("WaveClosing — 원인만 다르고 본문은 하나다")
class WaveClosingTest {

    private static final Instant CUTOFF = Instant.parse("2026-09-24T01:00:00Z");
    private static final Instant NOW = CUTOFF.minusSeconds(1800);
    private static final UUID CAMP_ID = Ids.newId();

    private final InMemoryFulfillmentRepositories repositories = new InMemoryFulfillmentRepositories();
    private final RecordingWaveEvents events = new RecordingWaveEvents();
    private final WaveClosing closing = new WaveClosing(repositories.waveRepository(),
            repositories.orderRepository(), events, repositories.referenceData(), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void 캠프를_등록한다() {
        repositories.addCamp(new Camp(CAMP_ID, "CAMP-TEST", Ids.newId(), GeoPoint.of(37.5663, 126.9779), true));
    }

    private Wave openWave() {
        return openWave(CUTOFF);
    }

    /** 자연키 {@code (campId, tier, cutoffAt)} 가 웨이브 하나를 정하므로, 둘을 만들려면 컷오프가 달라야 한다. */
    private Wave openWave(Instant cutoffAt) {
        Wave wave = Wave.open(Ids.newId(), CAMP_ID, ServiceTier.DAWN, cutoffAt);
        repositories.waveRepository().insertIfAbsent(wave);
        return wave;
    }

    private void admit(Wave wave) {
        repositories.orderRepository().insertIfAbsent(FulfillmentOrder.planned(Ids.newId(), Ids.newId(),
                wave.id(), CAMP_ID, Ids.newId(), Ids.newId(), wave.cutoffAt(),
                new TimeWindow(CUTOFF, CUTOFF.plusSeconds(3600)), false, null, NOW));
    }

    @Test
    void 열린_웨이브를_세어_닫고_원인을_남기고_wave_closed_를_낸다() {
        for (WaveCloseCause cause : WaveCloseCause.values()) {
            Wave wave = openWave(CUTOFF.plusSeconds(3600L * cause.ordinal()));
            admit(wave);
            admit(wave);

            WaveClosing.Outcome outcome = closing.closeIfOpen(wave.id(), cause);

            assertThat(outcome).isInstanceOfSatisfying(WaveClosing.Outcome.Closed.class, closed -> {
                assertThat(closed.campCode()).isEqualTo("CAMP-TEST");
                assertThat(closed.wave().status()).isEqualTo(WaveStatus.CLOSED);
                assertThat(closed.wave().orderCount()).isEqualTo(2);
                assertThat(closed.wave().closedAt()).as("컷오프 전이어도 지금 시각이다").isEqualTo(NOW)
                        .isBefore(closed.wave().cutoffAt());
                assertThat(closed.wave().closeCause()).isEqualTo(cause);
            });
            assertThat(repositories.waveRepository().findById(wave.id()).orElseThrow().closeCause())
                    .as("저장된다").isEqualTo(cause);
            assertThat(events.closed).last().extracting(Wave::id).isEqualTo(wave.id());
        }
    }

    @Test
    void 이미_닫힌_웨이브는_건드리지_않고_현재_모습을_돌려준다() {
        Wave wave = openWave();
        closing.closeIfOpen(wave.id(), WaveCloseCause.MANUAL);

        WaveClosing.Outcome second = closing.closeIfOpen(wave.id(), WaveCloseCause.SCHEDULED);

        assertThat(second).isInstanceOfSatisfying(WaveClosing.Outcome.NotOpen.class, notOpen ->
                assertThat(notOpen.wave().closeCause()).as("먼저 닫은 쪽이 남는다").isEqualTo(WaveCloseCause.MANUAL));
        assertThat(events.closed).as("wave.closed 는 한 번 — 두 번이면 하류가 두 번 계획한다").hasSize(1);
    }

    @Test
    void 없는_웨이브는_NotFound_다() {
        assertThat(closing.closeIfOpen(Ids.newId(), WaveCloseCause.MANUAL))
                .isInstanceOf(WaveClosing.Outcome.NotFound.class);
        assertThat(events.closed).isEmpty();
    }

    @Test
    void 캠프를_못_찾으면_예외로_그_트랜잭션을_실패시킨다() {
        // 좌표 없는 wave.closed 는 하류가 계획할 수 없는 이벤트다.
        Wave orphan = Wave.open(Ids.newId(), Ids.newId(), ServiceTier.DAWN, CUTOFF);
        repositories.waveRepository().insertIfAbsent(orphan);

        assertThatThrownBy(() -> closing.closeIfOpen(orphan.id(), WaveCloseCause.MANUAL))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("캠프를 찾지 못해");
        assertThat(events.closed).isEmpty();
    }
}
