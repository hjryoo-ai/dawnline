package com.dawnline.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * {@link MdcScope} 가 MDC 에 넣는 id 를 현재 스팬에도 단다 (DESIGN.md §9.3, ADR-062 결정 4).
 *
 * <p>TraceQL {@code { span.dawnline.wave_id = "…" }} 이 주문 트레이스와 계획 트레이스를 함께 돌려주는 것은 이 속성
 * 덕분이다 — 스모크가 전 구간을 보고, 여기서는 그 속성이 어디서 달리는지를 본다. 실제 SDK 로 스팬을 연다.
 */
class MdcScopeSpanTest {

    private final SdkTracerProvider provider = SdkTracerProvider.builder().build();

    @AfterEach
    void close() {
        provider.close();
        MDC.clear();
    }

    @Test
    void 여는_순간_id_를_현재_스팬에_단다_이름은_MDC_키의_규칙이다() {
        UUID orderId = UUID.randomUUID();
        UUID waveId = UUID.randomUUID();
        Span span = provider.get("test").spanBuilder("candidate").startSpan();
        Scope current = span.makeCurrent();
        try {
            MdcScope.builder().service("dispatch-service").orderId(orderId).waveId(waveId).run(() -> { });
        } finally {
            current.close();
            span.end();
        }

        Attributes attributes = ((ReadableSpan) span).toSpanData().getAttributes();
        assertThat(attributes.get(AttributeKey.stringKey("dawnline.wave_id"))).isEqualTo(waveId.toString());
        assertThat(attributes.get(AttributeKey.stringKey("dawnline.order_id"))).isEqualTo(orderId.toString());
        assertThat(attributes.get(AttributeKey.stringKey("dawnline.service")))
                .as("service 는 리소스 속성 service.name 이 있다 — 빼는 키").isNull();
    }

    @Test
    void 스팬이_없으면_MDC_만_넣는다() {
        assertThat(Span.current().getSpanContext().isValid()).as("전제 — 현재 스팬이 없다").isFalse();

        assertThatCode(() -> MdcScope.builder().waveId(UUID.randomUUID())
                .run(() -> assertThat(MDC.get(MdcKeys.WAVE_ID)).isNotNull()))
                .doesNotThrowAnyException();
    }
}
