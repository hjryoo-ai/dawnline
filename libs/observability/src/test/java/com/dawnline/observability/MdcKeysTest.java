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
                .containsExactlyInAnyOrder("service", "eventId", "orderId", "waveId", "routeId");
    }

    @Test
    void traceId와spanId_micrometer가쓰는키이름과같다() {
        // io.micrometer.tracing.otel.bridge.Slf4JEventListener 의 기본 키 이름.
        assertThat(MdcKeys.TRACE_ID).isEqualTo("traceId");
        assertThat(MdcKeys.SPAN_ID).isEqualTo("spanId");
    }
}
