package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.dispatch.domain.optimizer.strategy.BaselineNearestNeighbor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 내장 전략 목록 (DESIGN.md §6.6).
 *
 * <p>목록이 <strong>하나뿐</strong>이어야 한다. {@code PlanRunner}(Phase 3-5b)와
 * {@code tools/benchmark} 가 각자 목록을 들면 "벤치마크에서는 도는데 운영에서는 없는 전략" 이
 * 생긴다.
 *
 * <p>이름 → <em>새 인스턴스를 만드는 함수</em>다. 전략이 상태를 들면 계획 사이에 그 상태가 흐르고,
 * 그러면 같은 입력이 같은 결과를 낸다는 보장이 깨진다(불변규칙 12).
 */
public final class DispatchStrategies {

    private static final Map<String, Supplier<DispatchStrategy>> BUILT_IN = builtIn();

    private DispatchStrategies() {
    }

    private static Map<String, Supplier<DispatchStrategy>> builtIn() {
        Map<String, Supplier<DispatchStrategy>> strategies = new LinkedHashMap<>();
        strategies.put(BaselineNearestNeighbor.NAME, BaselineNearestNeighbor::new);
        return Map.copyOf(strategies);
    }

    /** 내장 전략 이름들. */
    public static List<String> names() {
        return List.copyOf(BUILT_IN.keySet());
    }

    /**
     * @param name 전략 이름
     */
    public static DispatchStrategy create(String name) {
        Supplier<DispatchStrategy> factory = BUILT_IN.get(name);
        if (factory == null) {
            throw new IllegalArgumentException(
                    "알 수 없는 전략: %s (내장 전략: %s)".formatted(name, names()));
        }
        return factory.get();
    }

    /** 이 이름이 내장 전략인가. */
    public static boolean contains(String name) {
        return BUILT_IN.containsKey(name);
    }
}
