package com.dawnline.tracking.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Ids;
import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.tracking.domain.ScanType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 발행하는 페이로드가 계약을 지키는가 (불변규칙 8, {@code contracts/events}).
 *
 * <p>{@code delivery.status.v1} 은 <strong>소비자가 먼저 정의한</strong> 계약이다 —
 * order-service 가 Phase 1 에 썼고 tracking 이 Phase 5-1b 에서 처음 발행한다. 그래서 여기서
 * 검사하는 방향이 중요하다: 코드가 계약을 따라가는지이지 그 반대가 아니다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("DeliveryPayloadContractTest — 발행이 계약을 지킨다")
class DeliveryPayloadContractTest {

    private static final EventContracts CONTRACTS = EventContracts.load();

    private static final Instant NOW = Instant.parse("2026-09-19T11:00:00Z");

    @ParameterizedTest
    @EnumSource(mode = EnumSource.Mode.EXCLUDE, names = "DEPARTED_CAMP")
    void 발행되는_스캔은_모두_계약을_지킨다(ScanType type) {
        // 종류를 열거하지 않는다 — 빼는 방식이다(CLAUDE.md). 새 ScanType 이 생기면 이 검사가
        // 자동으로 대상에 넣는다. 제외한 DEPARTED_CAMP 가 왜 제외인지는 아래가 말한다.
        DeliveryStatusPayload payload = DeliveryStatusPayload.of(Ids.newId(), 3,
                List.of(Ids.newId(), Ids.newId()), type, NOW,
                type == ScanType.FAILED ? "부재" : null);

        CONTRACTS.validatePayload(DeliveryStatusPayload.EVENT_TYPE,
                DeliveryStatusPayload.SCHEMA_VERSION, CONTRACTS.json().toTree(payload));
    }

    @Test
    void 캠프_출발은_이_계약의_값이_아니다() {
        // 위 검사가 DEPARTED_CAMP 를 **왜** 제외하는지를 말한다. 계약의 status enum 은 셋이고,
        // 없는 값을 내보내면 소비자는 그것을 조용히 무시한다(§4.7) — 무시는 어디에도 안 남는다.
        assertThat(ScanType.DEPARTED_CAMP.isPublished()).isFalse();

        assertThatThrownBy(() -> DeliveryStatusPayload.of(Ids.newId(), 1, List.of(Ids.newId()),
                ScanType.DEPARTED_CAMP, NOW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DEPARTED_CAMP");
    }

    @Test
    void 사유_없는_실패도_계약을_지킨다() {
        // failureReason 은 required 가 아니다. null 이면 직렬화에서 빠지고, 「칸이 있는데
        // 비어 있다」와 구별된다 — shipment_events.payload 를 NULL 로 두는 것과 같은 규칙이다.
        DeliveryStatusPayload payload = DeliveryStatusPayload.of(Ids.newId(), 1,
                List.of(Ids.newId()), ScanType.FAILED, NOW, null);

        CONTRACTS.validatePayload(DeliveryStatusPayload.EVENT_TYPE,
                DeliveryStatusPayload.SCHEMA_VERSION, CONTRACTS.json().toTree(payload));
    }

    @Test
    void 파티션_키는_라우트다() {
        // §4.1 — 같은 라우트의 진행 상태가 순서대로 온다는 것이 이 키 선택의 목적이다.
        UUID routeId = Ids.newId();
        DeliveryStatusPayload payload = DeliveryStatusPayload.of(routeId, 2, List.of(Ids.newId()),
                ScanType.ARRIVED, NOW, null);

        assertThat(payload.routeId()).isEqualTo(routeId);
        assertThat(DeliveryStatusPayload.AGGREGATE_TYPE).isEqualTo("Route");
    }
}
