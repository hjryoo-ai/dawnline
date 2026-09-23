package com.dawnline.ops.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/**
 * 두 읽기 모델의 차이를 <em>칸 단위로</em> 말한다 — 「O2 의 route_id: R2 ≠ R1」.
 *
 * <p>행 전체를 {@code isEqualTo} 로 견주면 실패 메시지가 UUID 로 된 두 벽이 되고, 어느 칸이
 * 갈렸는지 사람이 눈으로 찾아야 한다. 순서 검사의 실패는 <strong>어느 사실이 사라졌나</strong>를
 * 말해야 쓸모가 있다.
 */
public final class RowDiff {

    private RowDiff() {
    }

    /**
     * @param expected 기준 (표 → 키 → 칸 → 값)
     * @param actual   비교 대상
     * @param names    키를 사람이 읽는 이름으로
     * @return 갈린 칸들. 같으면 비어 있다
     */
    public static List<String> between(Map<String, ? extends Map<?, Map<String, Object>>> expected,
            Map<String, ? extends Map<?, Map<String, Object>>> actual, Function<UUID, String> names) {
        List<String> diffs = new ArrayList<>();
        for (String table : new TreeSet<>(union(expected.keySet(), actual.keySet()))) {
            Map<?, Map<String, Object>> left = expected.containsKey(table) ? expected.get(table) : Map.of();
            Map<?, Map<String, Object>> right = actual.containsKey(table) ? actual.get(table) : Map.of();
            Set<Object> keys = new TreeSet<>(Comparator.comparing(String::valueOf));
            keys.addAll(left.keySet());
            keys.addAll(right.keySet());
            for (Object key : keys) {
                Map<String, Object> l = left.get(key);
                Map<String, Object> r = right.get(key);
                if (l == null || r == null) {
                    diffs.add("%s %s: 행이 %s".formatted(table, show(key, names), l == null ? "더 있다" : "없다"));
                    continue;
                }
                for (String column : new TreeSet<>(union(l.keySet(), r.keySet()))) {
                    Object a = l.get(column);
                    Object b = r.get(column);
                    if (!Objects.equals(a, b)) {
                        diffs.add("%s %s.%s: 기준 %s ≠ %s".formatted(table, show(key, names), column,
                                show(a, names), show(b, names)));
                    }
                }
            }
        }
        return diffs;
    }

    private static String show(Object value, Function<UUID, String> names) {
        return value instanceof UUID id ? names.apply(id) : String.valueOf(value);
    }

    private static <T> Set<T> union(Set<T> a, Set<T> b) {
        Set<T> all = new HashSet<>(a);
        all.addAll(b);
        return all;
    }
}
