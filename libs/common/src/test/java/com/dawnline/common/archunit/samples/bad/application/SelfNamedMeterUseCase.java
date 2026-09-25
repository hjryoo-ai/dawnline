package com.dawnline.common.archunit.samples.bad.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 규칙 11 위반 표본 — 유스케이스가 미터를 헬퍼 없이 직접 등록한다 ([ADR-060] 결정 2).
 *
 * <p>두 줄 다 컴파일되고 동작한다. 그리고 둘 다 조용히 틀린다: 카운터의 이름은 §9.1 표와 대조되지 않고(표에 없는 이름이
 * 대시보드에 나타나거나, 표의 이름과 한 글자 달라 알림이 영원히 비어 있다), 게이지는 상태를 <strong>약한 참조</strong>로
 * 잡아 이 객체가 GC 되면 {@code NaN} 을 낸다 — 이 저장소에서 {@code NaN} 은 「모름」이라, 그 결함이 「모름」을 보는
 * 검사를 대상 없이 통과시킨 적이 있다(DESIGN.md §13 축 10 의 변종).
 */
public class SelfNamedMeterUseCase {

    private final AtomicLong stuck = new AtomicLong();

    /**
     * @param registry 레지스트리
     */
    public SelfNamedMeterUseCase(MeterRegistry registry) {
        Counter.builder("dawnline.self.named").register(registry);
        registry.gauge("dawnline.self.gauge", stuck);
    }
}
