package com.dawnline.tracking.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.AssignedStop;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.RouteAssignment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@code route.assigned} 페이로드 → 유스케이스 명령 변환.
 *
 * <p>입력을 <strong>계약의 예시 파일에서</strong> 읽는다(불변규칙 8). 손으로 만든 픽스처는
 * 발행자가 실제로 내는 모양과 갈라질 수 있고, 그 갈라짐은 이 테스트가 통과하는 동안 일어난다.
 * {@code route.assigned.v1.revised.example.json} 은 취소의 두 표현
 * ({@code status: CANCELLED} 와 {@code cancelledOrderIds})을 모두 담고 있다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("route.assigned 페이로드")
class RouteAssignedPayloadTest {

    /** 이 테스트에서 값 자체는 중요하지 않다 — 「있다/없다」가 검사 대상이다. */
    private static final Instant DEPARTURE = Instant.parse("2026-08-29T15:21:00Z");

    private static final EventContracts CONTRACTS = EventContracts.load();
    private static final EventJson JSON = CONTRACTS.json();

    private static final UUID CANCELLED_STOP_ORDER = UUID.fromString("01a04dad-80da-7fa7-ad00-3d1829eed7c6");
    private static final UUID LIVE_ORDER = UUID.fromString("01a04dad-80da-7707-a397-e3662354beef");
    private static final UUID CANCELLED_SIBLING = UUID.fromString("01a04dad-80da-7411-a096-70abfdde82ff");

    @Test
    void 예시는_계약을_만족한다() {
        // 전제다 — 예시가 계약을 벗어나 있으면 아래 검사들은 "발행자가 내는 모양" 을 보는 것이
        // 아니라 이 파일이 담고 있는 모양을 볼 뿐이다.
        CONTRACTS.validateRecord(read("route.assigned.v1.revised.example.json"));
    }

    @Test
    void 계약_예시를_그대로_명령으로_옮긴다() {
        RouteAssignment assignment = assignmentFrom("route.assigned.v1.revised.example.json");

        assertThat(assignment.routeId())
                .isEqualTo(UUID.fromString("01a04e09-854a-770c-b7b7-03325dccc708"));
        assertThat(assignment.revision()).isEqualTo(2);
        assertThat(assignment.stops()).hasSize(3);
        assertThat(assignment.stops().getFirst().plannedArrival())
                .isEqualTo(Instant.parse("2026-08-29T15:41:00Z"));
        assertThat(assignment.stops().getFirst().promisedEnd())
                .isEqualTo(Instant.parse("2026-08-29T18:00:00Z"));
    }

    @Test
    void stop_이_통째로_취소면_그_stop_의_주문이_전부_취소다() {
        // status: CANCELLED (seq 2).
        AssignedStop stop = assignmentFrom("route.assigned.v1.revised.example.json").stops().get(1);

        assertThat(stop.orderIds()).containsExactly(CANCELLED_STOP_ORDER);
        assertThat(stop.isCancelled(CANCELLED_STOP_ORDER)).isTrue();
    }

    @Test
    void 통합된_stop_의_부분_취소는_주문_단위로_남는다() {
        // seq 3 — 두 주문이 한 지점으로 묶였고 하나만 취소됐다 (§6.5 StopMerger, ADR-026).
        AssignedStop stop = assignmentFrom("route.assigned.v1.revised.example.json").stops().get(2);

        assertThat(stop.orderIds()).containsExactly(LIVE_ORDER, CANCELLED_SIBLING);
        assertThat(stop.isCancelled(CANCELLED_SIBLING)).isTrue();
        assertThat(stop.isCancelled(LIVE_ORDER))
                .as("같은 stop 이라고 함께 죽지 않는다 — shipments 의 PK 는 order_id 다")
                .isFalse();
    }

    @Test
    void 취소_표기가_없으면_취소가_없다() {
        // seq 1 — cancelledOrderIds 도 없고 status 는 PLANNED 다. 스키마의 default 와 같은 뜻이다.
        AssignedStop stop = assignmentFrom("route.assigned.v1.revised.example.json").stops().getFirst();

        assertThat(stop.cancelledOrderIds()).isEmpty();
    }

    @Test
    void 최초_확정_예시는_취소가_하나도_없다() {
        RouteAssignment assignment = assignmentFrom("route.assigned.v1.example.json");

        assertThat(assignment.revision()).isEqualTo(1);
        assertThat(assignment.stops()).isNotEmpty()
                .allSatisfy(stop -> assertThat(stop.cancelledOrderIds()).isEmpty());
    }

    @Test
    void 약속창_없는_stop_은_지어내지_않고_멈춘다() {
        // Phase 5-1a 이전에 발행된 이벤트다. 창을 지어내면 at-risk 판정이 거짓 위에서 돌고,
        // 그 거짓은 이벤트를 받은 쪽에서 구별할 수 없다 (§5.4, contracts/events/README.md §5).
        RouteAssignedPayload payload = new RouteAssignedPayload(UUID.randomUUID(), 1,
                UUID.randomUUID(), new RouteAssignedPayload.Summary(DEPARTURE),
                List.of(new RouteAssignedPayload.StopPayload(1, List.of(UUID.randomUUID()), null,
                        Instant.parse("2026-08-29T15:41:00Z"), null, "PLANNED")));

        assertThatThrownBy(payload::toAssignment)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promisedWindow")
                .hasMessageContaining("latest");
    }

    @Test
    void 계획_출발_시각이_없는_라우트도_지어내지_않고_멈춘다() {
        // 같은 부류다 (Phase 5-1b 계약). 출발 시각을 지어내면 「늦게 출발했다」가 거짓 위에서
        // 판정되고, at-risk 를 받은 dispatch 는 그 거짓을 구별할 수 없다.
        RouteAssignedPayload payload = new RouteAssignedPayload(UUID.randomUUID(), 1,
                UUID.randomUUID(), null,
                List.of(new RouteAssignedPayload.StopPayload(1, List.of(UUID.randomUUID()), null,
                        Instant.parse("2026-08-29T15:41:00Z"),
                        new RouteAssignedPayload.Window(null, Instant.parse("2026-08-29T18:00:00Z")),
                        "PLANNED")));

        assertThatThrownBy(payload::toAssignment)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plannedDeparture")
                .hasMessageContaining("latest");
    }

    @Test
    void 예시의_계획_출발_시각을_읽는다() {
        RouteAssignment assignment = assignmentFrom("route.assigned.v1.example.json");

        assertThat(assignment.plannedDeparture())
                .as("summary.plannedDeparture — DEPARTED_CAMP 편차의 기준이다")
                .isEqualTo(Instant.parse("2026-08-29T15:21:00Z"));
    }

    private static RouteAssignment assignmentFrom(String exampleFile) {
        EventEnvelope<RouteAssignedPayload> envelope =
                JSON.readEnvelope(read(exampleFile), RouteAssignedPayload.class);
        return envelope.payload().toAssignment();
    }

    private static String read(String exampleFile) {
        Path file = CONTRACTS.contractsDirectory().resolve("examples").resolve(exampleFile);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("계약 예시를 읽지 못했습니다: " + file, e);
        }
    }
}
