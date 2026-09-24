package com.dawnline.ops.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.ops.domain.CoreService;
import java.util.HashSet;
import java.util.Set;
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

    @Test
    void 조기_마감은_웨이브를_가리키고_이유를_싣는다() {
        OpsCommand close = new OpsCommand.CloseWave(A, "피크 대비 선마감");

        assertThat(close.action()).isEqualTo("CLOSE_WAVE");
        assertThat(close.targetType()).isEqualTo("WAVE");
        assertThat(close.targetId()).isEqualTo(A);
        assertThat(close.arguments()).containsExactly(
                java.util.Map.entry("waveId", A), java.util.Map.entry("reason", "피크 대비 선마감"));
    }

    @Test
    void 재큐는_outbox_행을_가리키고_어느_코어인지를_먼저_싣는다() {
        OpsCommand requeue = new OpsCommand.RequeueOutbox(CoreService.TRACKING, B);

        assertThat(requeue.action()).isEqualTo("REQUEUE_OUTBOX");
        assertThat(requeue.targetType()).isEqualTo("OUTBOX_EVENT");
        assertThat(requeue.targetId()).isEqualTo(B);
        assertThat(requeue.arguments()).containsExactly(
                java.util.Map.entry("service", "tracking"), java.util.Map.entry("id", B));
    }

    @Test
    void 경로의_service_는_넷_중_하나이고_그_밖은_없다() {
        assertThat(CoreService.fromPath("fulfillment")).contains(CoreService.FULFILLMENT);
        assertThat(CoreService.fromPath("ops-api")).as("ops-api 의 outbox 관리 경로는 꺼져 있다").isEmpty();
        assertThat(CoreService.fromPath("Order")).as("경로는 한 가지 모양만 있다").isEmpty();
        assertThat(java.util.Arrays.stream(CoreService.values()).map(CoreService::path))
                .containsExactly("order", "fulfillment", "dispatch", "tracking");
    }

    @Test
    void ACTIONS_는_허용된_하위_타입의_ACTION_전부다() throws ReflectiveOperationException {
        // 미리 등록하는 목록이 커맨드 하나를 빠뜨리면 그 커맨드의 첫 UNKNOWN 은 알림에 안 잡힌다(§9.1).
        // 목록을 열거하지 않고 sealed 의 허용 목록에서 읽어 대조한다(CLAUDE.md 「서로를 비추는 목록에는 대조 검사」).
        Set<String> declared = new HashSet<>();
        for (Class<?> permitted : OpsCommand.class.getPermittedSubclasses()) {
            declared.add((String) permitted.getField("ACTION").get(null));
        }

        assertThat(OpsCommand.ACTIONS).containsExactlyInAnyOrderElementsOf(declared)
                .hasSize(OpsCommand.class.getPermittedSubclasses().length);
    }
}
