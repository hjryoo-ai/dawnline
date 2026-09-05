package com.dawnline.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 권역 값 객체 (ADR-021). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ZoneTest {

    @Test
    void geohash5_는_5자여야_한다() {
        // CHAR(5) 컬럼이라 공백이 채워져 오는데, 그것을 그대로 키로 쓰면 캐시가 어긋난다.
        // 어댑터가 strip 하고 여기서 길이를 못 박는다.
        assertThatThrownBy(() -> new Zone(UUID.randomUUID(), "wydm", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Zone(UUID.randomUUID(), "wydm7 ", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 권역은_항상_캠프를_갖는다() {
        assertThatThrownBy(() -> new Zone(UUID.randomUUID(), "wydm7", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 정상_권역() {
        UUID id = UUID.randomUUID();
        UUID campId = UUID.randomUUID();

        Zone zone = new Zone(id, "wydm7", campId);

        assertThat(zone.id()).isEqualTo(id);
        assertThat(zone.geohash5()).isEqualTo("wydm7");
        assertThat(zone.campId()).isEqualTo(campId);
    }
}
