package com.dawnline.fulfillment.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.WaveStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** {@code waves} 왕복 매핑 (DESIGN.md §5.2). DB 는 {@code FulfillmentPersistenceIT} 가 본다. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WaveEntityTest {

    private static final UUID CAMP_ID = UUID.randomUUID();
    private static final Instant CUTOFF = Instant.parse("2026-09-05T01:00:00Z");

    private static Wave open() {
        return Wave.open(UUID.randomUUID(), CAMP_ID, ServiceTier.DAWN, CUTOFF);
    }

    @Test
    void 열린_웨이브가_손실_없이_왕복한다() {
        Wave wave = open();

        Wave restored = WaveEntity.from(wave).toDomain();

        assertThat(restored.id()).isEqualTo(wave.id());
        assertThat(restored.campId()).isEqualTo(CAMP_ID);
        assertThat(restored.serviceTier()).isEqualTo(ServiceTier.DAWN);
        assertThat(restored.cutoffAt()).isEqualTo(CUTOFF);
        assertThat(restored.status()).isEqualTo(WaveStatus.OPEN);
        assertThat(restored.orderCount()).isZero();
        assertThat(restored.closedAt()).isNull();
    }

    @Test
    void 마감된_웨이브의_카운트와_마감_시각이_왕복한다() {
        Wave wave = open();
        wave.addOrder();
        wave.addOrder();
        wave.beginClosing();
        Instant closedAt = CUTOFF.plusSeconds(120);
        wave.close(closedAt);

        Wave restored = WaveEntity.from(wave).toDomain();

        assertThat(restored.status()).isEqualTo(WaveStatus.CLOSED);
        assertThat(restored.orderCount()).isEqualTo(2);
        assertThat(restored.closedAt()).isEqualTo(closedAt);
    }

    @Test
    void 전이만_반영하고_자연키는_건드리지_않는다() {
        // (campId, tier, cutoffAt) 이 바뀌면 그것은 다른 웨이브다.
        Wave wave = open();
        WaveEntity entity = WaveEntity.from(wave);
        wave.addOrder();
        wave.beginClosing();

        entity.applyStateOf(wave);

        Wave restored = entity.toDomain();
        assertThat(restored.status()).isEqualTo(WaveStatus.CLOSING);
        assertThat(restored.orderCount()).isEqualTo(1);
        assertThat(restored.campId()).isEqualTo(CAMP_ID);
        assertThat(restored.cutoffAt()).isEqualTo(CUTOFF);
    }

    @Test
    void 다른_웨이브의_상태는_반영하지_않는다() {
        WaveEntity entity = WaveEntity.from(open());

        assertThatThrownBy(() -> entity.applyStateOf(open()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 새_엔티티는_버전이_0_이고_상태를_노출한다() {
        Wave wave = open();
        WaveEntity entity = WaveEntity.from(wave);

        assertThat(entity.id()).isEqualTo(wave.id());
        assertThat(entity.status()).isEqualTo(WaveStatus.OPEN);
        assertThat(entity.version()).isZero();
    }
}
