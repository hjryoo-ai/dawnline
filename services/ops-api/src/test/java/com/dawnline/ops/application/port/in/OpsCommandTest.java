package com.dawnline.ops.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 감사 행의 세 칸과 인자 — §5.5 「커맨드 위임」 표와 같은 이름이다. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("OpsCommand — 감사 행에 무엇이 적히나")
class OpsCommandTest {

    private static final UUID A = UUID.fromString("0199a000-0000-7000-8000-0000000000a1");
    private static final UUID B = UUID.fromString("0199a000-0000-7000-8000-0000000000b1");
    private static final UUID C = UUID.fromString("0199a000-0000-7000-8000-0000000000c1");

    @Test
    void 재계획은_웨이브를_가리키고_준_인자만_싣는다() {
        OpsCommand full = new OpsCommand.RunPlan(A, B, "sweep-greedy-nn+ls", "FAST");
        OpsCommand bare = new OpsCommand.RunPlan(A, null, null, null);

        assertThat(full.action()).isEqualTo("RUN_PLAN");
        assertThat(full.targetType()).isEqualTo("WAVE");
        assertThat(full.targetId()).isEqualTo(A);
        assertThat(full.arguments()).containsKeys("waveId", "campId", "strategy", "mode");
        assertThat(bare.arguments()).as("주지 않은 것은 null 로 적지 않는다").containsOnlyKeys("waveId");
    }

    @Test
    void 재배정과_취소는_주문을_가리킨다() {
        OpsCommand reassign = new OpsCommand.ReassignStop(B, C, A);
        OpsCommand cancel = new OpsCommand.CancelOrder(C, null);

        assertThat(reassign.action()).isEqualTo("REASSIGN_STOP");
        assertThat(reassign.targetType()).isEqualTo("ORDER");
        assertThat(reassign.targetId()).isEqualTo(C);
        assertThat(reassign.arguments()).containsOnlyKeys("routeId", "orderId", "targetRouteId");
        assertThat(cancel.action()).isEqualTo("CANCEL_ORDER");
        assertThat(cancel.targetType()).isEqualTo("ORDER");
        assertThat(cancel.targetId()).isEqualTo(C);
        assertThat(cancel.arguments()).containsOnlyKeys("orderId");
    }
}
