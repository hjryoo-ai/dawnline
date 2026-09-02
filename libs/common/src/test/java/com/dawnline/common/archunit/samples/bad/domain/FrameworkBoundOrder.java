package com.dawnline.common.archunit.samples.bad.domain;

import com.dawnline.common.Money;
import org.springframework.stereotype.Component;

/**
 * 규칙 검증용 표본: <strong>일부러</strong> Spring 에 의존하는 도메인 클래스.
 *
 * <p>{@code DOMAIN_IS_FRAMEWORK_FREE} 규칙이 이것을 반드시 잡아내야 한다.
 *
 * <p>현실에서 이 실수가 나오는 경로는 "도메인 객체를 빈으로 주입받고 싶다" 는 요구다.
 * 그 순간 {@code dispatch-service} 의 {@code domain.optimizer} 는 Spring 컨텍스트 없이는
 * 인스턴스화할 수 없게 되고, 벤치마크 도구({@code tools/benchmark})에서 그대로 실행한다는
 * 전제가 깨진다 (CLAUDE.md 불변규칙 5, DESIGN.md §3.4).
 */
@Component
public final class FrameworkBoundOrder {

    private final Money total;

    public FrameworkBoundOrder() {
        this.total = Money.krw(0);
    }

    public Money total() {
        return total;
    }
}
