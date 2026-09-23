package com.dawnline.ops.support;

import com.dawnline.ops.application.port.out.OrderColumn;
import com.dawnline.ops.application.port.out.OrderRows;
import com.dawnline.ops.application.port.out.Patch;
import com.dawnline.ops.application.port.out.RouteColumn;
import com.dawnline.ops.application.port.out.RouteRows;
import com.dawnline.ops.application.port.out.WaveColumn;
import com.dawnline.ops.application.port.out.WaveRows;
import com.dawnline.ops.domain.DeliveryOutcome;
import com.dawnline.ops.domain.OrderStatus;
import com.dawnline.ops.domain.RouteStatus;
import com.dawnline.ops.domain.WaveStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 세 행 포트의 메모리 구현 — JDBC 어댑터의 <em>의미</em>만 옮긴다(키만으로 행 만들기 · 패치의
 * 칸만 쓰기 · {@code setIfAbsent} 는 COALESCE · 개수는 다시 세기 · 계획이 없는 라우트는 세지 않기).
 *
 * <p>잠금은 흉내 내지 않는다 — 단위 테스트는 한 스레드다. 잠금과 SQL 은 {@code ProjectionShuffleIT}
 * 가 실제 PostgreSQL 에서 본다. 여기서 보는 것은 판정이 순서와 무관한가 하나다.
 *
 * <p>행은 칸 이름(소문자) → 값의 맵이라 어댑터가 쓰는 칸 이름과 같은 열쇠로 비교된다.
 * {@code updated_at} 은 적지 않는다 — 사실이 아니라 프로젝션의 기록이고, 순서 비교에서 빠지는 칸이다.
 */
public final class InMemoryReadModel {

    private final Map<UUID, Map<String, Object>> orders = new HashMap<>();
    private final Map<UUID, Map<String, Object>> routes = new HashMap<>();
    private final Map<UUID, Map<String, Object>> waves = new HashMap<>();

    /** 쓰기 기록 — 패치마다 칸 집합 하나. {@link #drainWrites()} 가 비운다. */
    private final List<Set<Enum<?>>> writes = new ArrayList<>();

    private final OrderRows orderRows = new Orders();
    private final RouteRows routeRows = new Routes();
    private final WaveRows waveRows = new Waves();

    /** {@code rm_orders} 포트. */
    public OrderRows orderRows() {
        return orderRows;
    }

    /** {@code rm_routes} 포트. */
    public RouteRows routeRows() {
        return routeRows;
    }

    /** {@code rm_waves} 포트. */
    public WaveRows waveRows() {
        return waveRows;
    }

    /** 세 표 전부 — 표 → 키 → 칸 → 값. 순서 비교의 대상이다. */
    public Map<String, Map<UUID, Map<String, Object>>> snapshot() {
        Map<String, Map<UUID, Map<String, Object>>> tables = new TreeMap<>();
        tables.put("rm_orders", copy(orders));
        tables.put("rm_routes", copy(routes));
        tables.put("rm_waves", copy(waves));
        return tables;
    }

    /** 지난 호출 뒤로 적힌 패치들의 칸 집합 — 어느 사실이 어느 칸을 썼는가를 보는 검사가 쓴다. */
    public List<Set<Enum<?>>> drainWrites() {
        List<Set<Enum<?>>> drained = new ArrayList<>(writes);
        writes.clear();
        return drained;
    }

    /** 한 주문 행. */
    public Map<String, Object> order(UUID id) {
        return Objects.requireNonNull(orders.get(id), () -> "주문 행이 없다: " + id);
    }

    /** 한 라우트 행. */
    public Map<String, Object> route(UUID id) {
        return Objects.requireNonNull(routes.get(id), () -> "라우트 행이 없다: " + id);
    }

    /** 한 웨이브 행. */
    public Map<String, Object> wave(UUID id) {
        return Objects.requireNonNull(waves.get(id), () -> "웨이브 행이 없다: " + id);
    }

    private final class Orders implements OrderRows {

        @Override
        public Map<UUID, OrderRow> lock(Collection<UUID> orderIds) {
            Map<UUID, OrderRow> rows = new HashMap<>();
            for (UUID id : orderIds) {
                Map<String, Object> row = orders.computeIfAbsent(id, k -> new TreeMap<>());
                rows.put(id, new OrderRow(
                        enumOf(OrderStatus.class, row.get("order_status")),
                        enumOf(DeliveryOutcome.class, row.get("delivery_outcome")),
                        (UUID) row.get("route_id"),
                        (Instant) row.get("planned_as_of"),
                        (Instant) row.get("eta_as_of")));
            }
            return rows;
        }

        @Override
        public void write(UUID orderId, Patch<OrderColumn> patch, Instant touchedAt) {
            apply(orders, orderId, patch);
        }
    }

    private final class Routes implements RouteRows {

        @Override
        public Map<UUID, RouteRow> lock(Collection<UUID> routeIds) {
            Map<UUID, RouteRow> rows = new HashMap<>();
            for (UUID id : routeIds) {
                Map<String, Object> row = routes.computeIfAbsent(id, k -> new TreeMap<>());
                rows.put(id, new RouteRow((Integer) row.get("revision"),
                        enumOf(RouteStatus.class, row.get("status"))));
            }
            return rows;
        }

        @Override
        public void write(UUID routeId, Patch<RouteColumn> patch) {
            apply(routes, routeId, patch);
        }

        @Override
        public void recount(Collection<UUID> routeIds) {
            for (UUID routeId : routeIds) {
                Map<String, Object> route = routes.get(routeId);
                if (route == null || route.get("revision") == null) {
                    continue;
                }
                route.put("completed_count", countOrders(routeId, "COMPLETED"));
                route.put("failed_count", countOrders(routeId, "FAILED"));
            }
        }

        private long countOrders(UUID routeId, String outcome) {
            return orders.values().stream()
                    .filter(o -> routeId.equals(o.get("route_id")) && outcome.equals(o.get("delivery_outcome")))
                    .count();
        }
    }

    private final class Waves implements WaveRows {

        @Override
        public WaveRow lock(UUID waveId) {
            Map<String, Object> row = waves.computeIfAbsent(waveId, k -> new TreeMap<>());
            return new WaveRow(enumOf(WaveStatus.class, row.get("status")));
        }

        @Override
        public void write(UUID waveId, Patch<WaveColumn> patch) {
            apply(waves, waveId, patch);
        }

        @Override
        public void recountOrders(UUID waveId) {
            waves.get(waveId).put("order_count",
                    orders.values().stream().filter(o -> waveId.equals(o.get("wave_id"))).count());
        }
    }

    private static Map<UUID, Map<String, Object>> copy(Map<UUID, Map<String, Object>> table) {
        Map<UUID, Map<String, Object>> copy = new TreeMap<>();
        table.forEach((k, v) -> copy.put(k, new TreeMap<>(v)));
        return copy;
    }

    private <C extends Enum<C>> void apply(Map<UUID, Map<String, Object>> table, UUID key, Patch<C> patch) {
        if (!patch.isEmpty()) {
            writes.add(new HashSet<>(patch.writes().keySet()));
        }
        Map<String, Object> row = Objects.requireNonNull(table.get(key), () -> "잠그지 않은 행에 썼다: " + key);
        patch.writes().forEach((column, write) -> {
            String name = column.name().toLowerCase(Locale.ROOT);
            if (write.ifAbsent()) {
                row.putIfAbsent(name, write.value());
            } else {
                row.put(name, write.value());
            }
        });
    }

    private static <E extends Enum<E>> @Nullable E enumOf(Class<E> type, @Nullable Object value) {
        return value == null ? null : Enum.valueOf(type, (String) value);
    }
}
