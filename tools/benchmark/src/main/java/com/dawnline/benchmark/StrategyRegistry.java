package com.dawnline.benchmark;

import com.dawnline.dispatch.domain.optimizer.DispatchStrategy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 이름 → 전략 (DESIGN.md §6.6).
 *
 * <p>전략을 <strong>실행할 때마다 새로 만든다</strong>. 전략이 상태를 들면 반복 측정 사이에 그 상태가
 * 흘러 두 번째 회차가 더 빨라 보인다 — seed 가 같으면 결과가 같아야 한다는 규칙(불변규칙 12)이
 * 측정에서 깨지는 자리다.
 */
public final class StrategyRegistry {

    private final Map<String, Supplier<DispatchStrategy>> factories = new LinkedHashMap<>();

    /** 기본 등록: 지금은 비용 상한 하나뿐이고, Phase 3-3·3-4 에서 실제 전략이 붙는다. */
    public static StrategyRegistry standard() {
        StrategyRegistry registry = new StrategyRegistry();
        registry.register(UnassignAllStrategy.NAME, UnassignAllStrategy::new);
        return registry;
    }

    /**
     * @param name    전략 이름
     * @param factory 새 인스턴스를 만드는 함수
     */
    public void register(String name, Supplier<DispatchStrategy> factory) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(factory, "factory");
        if (factories.putIfAbsent(name, factory) != null) {
            throw new IllegalArgumentException("이미 등록된 전략입니다: " + name);
        }
    }

    /**
     * @param name 전략 이름
     */
    public DispatchStrategy create(String name) {
        Supplier<DispatchStrategy> factory = factories.get(name);
        if (factory == null) {
            throw new IllegalArgumentException(
                    "알 수 없는 전략: %s (등록된 전략: %s)".formatted(name, names()));
        }
        return factory.get();
    }

    /** 등록된 이름들 (등록 순서). */
    public List<String> names() {
        return List.copyOf(factories.keySet());
    }
}
