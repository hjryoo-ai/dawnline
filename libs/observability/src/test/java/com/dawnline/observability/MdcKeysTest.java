package com.dawnline.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** MDC 키 상수와 소유권 규칙(DESIGN.md §9.3)을 지키는 테스트. */
class MdcKeysTest {

    @Test
    void MANAGED_중복이없다() {
        assertThat(MdcKeys.MANAGED).doesNotHaveDuplicates();
    }

    @Test
    void MANAGED_traceId와spanId를포함하지않는다() {
        // 이 둘의 주인은 Micrometer Tracing 의 Slf4JEventListener 다.
        // 우리가 관리 목록에 넣으면 요청 종료 시 지워 버려 스팬 경계와 로그가 어긋난다.
        assertThat(MdcKeys.MANAGED).doesNotContain(MdcKeys.TRACE_ID, MdcKeys.SPAN_ID);
    }

    @Test
    void MANAGED_설계서9_3이요구하는애플리케이션소유키를모두담는다() {
        assertThat(MdcKeys.MANAGED)
                .containsExactlyInAnyOrder("service", "eventId", "orderId", "waveId", "routeId", "auditId");
    }

    @Test
    void 스팬_속성_이름은_MDC_키에서_규칙으로_나온다() {
        // TraceQL { span.dawnline.wave_id = "…" } 가 이 이름을 쓴다(DESIGN.md §9.2 · §9.3, ADR-062 결정 4).
        assertThat(MdcKeys.spanAttribute(MdcKeys.WAVE_ID)).isEqualTo("dawnline.wave_id");
        assertThat(MdcKeys.spanAttribute(MdcKeys.ORDER_ID)).isEqualTo("dawnline.order_id");
        assertThat(MdcKeys.spanAttribute(MdcKeys.ROUTE_ID)).isEqualTo("dawnline.route_id");
        assertThat(MdcKeys.spanAttribute(MdcKeys.EVENT_ID)).isEqualTo("dawnline.event_id");
        assertThat(MdcKeys.spanAttribute(MdcKeys.AUDIT_ID)).isEqualTo("dawnline.audit_id");
    }

    @Test
    void 스팬에_다는_키는_관리_키에서_이유를_적은_것만_뺀다() {
        // 빼는 방식(CLAUDE.md) — 새 MDC 키는 이유를 적어 빼지 않는 한 스팬에도 달린다.
        assertThat(MdcKeys.MANAGED).containsAll(MdcKeys.NOT_SPAN_ATTRIBUTES.keySet());
        assertThat(MdcKeys.NOT_SPAN_ATTRIBUTES.values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
        assertThat(MdcKeys.SPAN_ATTRIBUTE_KEYS)
                .containsExactlyElementsOf(MdcKeys.MANAGED.stream()
                        .filter(key -> !MdcKeys.NOT_SPAN_ATTRIBUTES.containsKey(key)).toList())
                .contains(MdcKeys.ORDER_ID, MdcKeys.WAVE_ID);
    }

    @Test
    void traceId와spanId_micrometer가쓰는키이름과같다() {
        // io.micrometer.tracing.otel.bridge.Slf4JEventListener 의 기본 키 이름.
        assertThat(MdcKeys.TRACE_ID).isEqualTo("traceId");
        assertThat(MdcKeys.SPAN_ID).isEqualTo("spanId");
    }
}
