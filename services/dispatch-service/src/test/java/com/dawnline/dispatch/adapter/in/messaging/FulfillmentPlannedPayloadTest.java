package com.dawnline.dispatch.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.ValidationException;
import com.dawnline.dispatch.application.port.in.PlannedOrderSnapshot;
import com.dawnline.messaging.contract.EventContracts;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 계약 예시가 그대로 스냅샷이 되는지 (계약 README §3 — 소비자는 examples 로 검증한다). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FulfillmentPlannedPayloadTest {

    private static final EventContracts CONTRACTS = EventContracts.load();

    private static JsonNode payloadOf(String example) {
        return CONTRACTS.readTree(CONTRACTS.contractsDirectory().resolve(Path.of("examples", example)))
                .get("payload");
    }

    @Test
    void 계획된_주문은_후보다() {
        assertThat(FulfillmentPlannedPayload.isCandidate(
                payloadOf("fulfillment.planned.v1.example.json"))).isTrue();
    }

    @Test
    void 배차_불가는_후보가_아니다() {
        // fulfillment 가 이미 내린 정상 판정이다. dispatch 에게는 계획할 것이 없다는 뜻이다.
        assertThat(FulfillmentPlannedPayload.isCandidate(
                payloadOf("fulfillment.planned.v1.unserviceable.example.json"))).isFalse();
    }

    @Test
    void 계약_예시가_그대로_스냅샷이_된다() {
        JsonNode payload = payloadOf("fulfillment.planned.v1.example.json");

        PlannedOrderSnapshot snapshot = FulfillmentPlannedPayload.toSnapshot(payload);

        assertThat(snapshot.orderId().toString()).isEqualTo(payload.get("orderId").asString());
        assertThat(snapshot.waveId().toString()).isEqualTo(payload.get("waveId").asString());
        assertThat(snapshot.campId().toString()).isEqualTo(payload.get("campId").asString());
        assertThat(snapshot.location().lat())
                .isEqualTo(payload.get("address").get("lat").doubleValue());
        assertThat(snapshot.weightG()).isEqualTo(payload.get("parcel").get("weightG").intValue());
        assertThat(snapshot.serviceSeconds())
                .isEqualTo(FulfillmentPlannedPayload.DEFAULT_SERVICE_SECONDS);
    }

    @Test
    void 계약에_우선도가_없어_모든_후보가_0_이다() {
        // 그래서 §6.3 의 PRIORITY_BOOST 는 운영에서 한 번도 발화하지 않는다. 우선도의 출처를
        // 정하는 것은 계약 변경이고, serviceTier 로 유추하면 "DAWN 이 곧 VIP" 라는 정책을
        // 코드가 몰래 정하는 셈이다. 이 테스트는 그 사실을 눈에 보이게 고정한다.
        assertThat(FulfillmentPlannedPayload.toSnapshot(
                payloadOf("fulfillment.planned.v1.example.json")).priority()).isZero();
    }

    @Test
    void 판정_입력에는_주소도_고객도_없다() {
        // 담지 않으면 로그에 샐 수도 없다 (CLAUDE.md — 전체 주소·고객 식별 정보 로그 금지).
        PlannedOrderSnapshot snapshot =
                FulfillmentPlannedPayload.toSnapshot(payloadOf("fulfillment.planned.v1.example.json"));

        assertThat(snapshot.toString())
                .doesNotContain("customerId")
                .doesNotContain("addressLine");
    }

    @Test
    void 오프셋이_붙은_시각도_같은_순간으로_읽는다() {
        // 계약 예시는 +09:00 오프셋으로 적혀 있고 도메인은 Instant 다. 문자열을 비교하면
        // 같은 순간을 다르다고 말하게 된다.
        JsonNode payload = payloadOf("fulfillment.planned.v1.revised.example.json");

        PlannedOrderSnapshot snapshot = FulfillmentPlannedPayload.toSnapshot(payload);

        assertThat(snapshot.promised().start()).isEqualTo(
                java.time.OffsetDateTime.parse(
                        payload.get("promisedWindow").get("start").asString()).toInstant());
        assertThat(snapshot.promised().end()).isAfter(snapshot.promised().start());
    }

    @Test
    void 필수_필드가_없으면_이름과_함께_실패한다() {
        JsonNode broken = CONTRACTS.json().readTree("""
                {"outcome":"PLANNED","orderId":"01a04dad-80da-7f6e-a63a-e91c103516b0"}""");

        assertThatThrownBy(() -> FulfillmentPlannedPayload.toSnapshot(broken))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("address");
    }
}
