package com.dawnline.messaging.tracing;

import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.outbox.TraceparentSupplier;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 현재 스팬의 {@code traceparent} — Micrometer Tracing 의 전파기가 쓰는 값 그대로 (DESIGN.md §9.2).
 *
 * <p>손으로 {@code 00-<trace>-<span>-<flags>} 를 조립하지 않는다. 전파 형식은 {@code management.tracing.propagation.produce}
 * 가 정하고(W3C), 샘플링 플래그까지 전파기가 안다 — 같은 값을 두 곳에서 만들면 언젠가 갈린다. 전파기가 W3C 가 아니면
 * {@code traceparent} 가 없고 이 제공자는 비어 있다.
 */
public final class TracerTraceparentSupplier implements TraceparentSupplier {

    private final Tracer tracer;
    private final Propagator propagator;

    /**
     * @param tracer     트레이서
     * @param propagator 전파기
     */
    public TracerTraceparentSupplier(Tracer tracer, Propagator propagator) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.propagator = Objects.requireNonNull(propagator, "propagator");
    }

    @Override
    public Optional<String> currentTraceparent() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return Optional.empty();
        }
        Map<String, String> carrier = new HashMap<>(4);
        propagator.inject(context, carrier, Map::put);
        return Optional.ofNullable(carrier.get(EventHeaders.TRACEPARENT));
    }
}
