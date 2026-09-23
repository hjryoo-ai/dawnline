package com.dawnline.ops.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PatchTest {

    @Test
    void null_을_적을_수_없다_부재는_값이_아니다() {
        // ADR-051 결정 2 를 타입이 지킨다. 모르는 칸은 패치에 넣지 않는 것으로만 표현된다.
        Patch<OrderColumn> patch = Patch.of(OrderColumn.class);

        assertThatThrownBy(() -> patch.set(OrderColumn.ETA_AT, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("부재는 값이 아니다");
        assertThatThrownBy(() -> patch.setIfAbsent(OrderColumn.CAMP_ID, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 적은_칸만_들고_있다() {
        UUID camp = UUID.randomUUID();
        Patch<RouteColumn> patch = Patch.of(RouteColumn.class)
                .setIfAbsent(RouteColumn.CAMP_ID, camp)
                .set(RouteColumn.AT_RISK, true);

        assertThat(patch.isEmpty()).isFalse();
        assertThat(patch.writes()).containsOnlyKeys(RouteColumn.CAMP_ID, RouteColumn.AT_RISK);
        assertThat(patch.writes().get(RouteColumn.CAMP_ID)).isEqualTo(new Patch.Write(camp, true));
        assertThat(patch.writes().get(RouteColumn.AT_RISK)).isEqualTo(new Patch.Write(true, false));
        assertThat(Patch.of(WaveColumn.class).isEmpty()).isTrue();
    }
}
