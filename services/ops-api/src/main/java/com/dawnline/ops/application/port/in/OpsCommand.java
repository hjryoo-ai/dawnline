package com.dawnline.ops.application.port.in;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 코어로 위임하는 운영자 커맨드 (DESIGN.md §5.5 「커맨드 위임」 표).
 *
 * <p>각 커맨드는 감사 행의 세 칸({@code action}·{@code target_type}·{@code target_id})과 {@code request}
 * JSONB 에 들어갈 인자를 스스로 말한다. 인자는 경로 변수·쿼리·본문뿐이고 <strong>값이 있는 것만</strong>
 * 싣는다 — 주지 않은 {@code mode} 를 {@code null} 로 적으면 「기본값을 골랐다」와 「보내지 않았다」가 같아진다.
 */
public sealed interface OpsCommand {

    /** @return {@code audit_logs.action} */
    String action();

    /** @return {@code audit_logs.target_type} */
    String targetType();

    /** @return {@code audit_logs.target_id} */
    UUID targetId();

    /** @return {@code audit_logs.request} 에 들어갈 인자 — 값이 있는 것만, 선언 순서대로 */
    Map<String, Object> arguments();

    /**
     * 재계획 — dispatch {@code POST /plans/{waveId}/run}.
     *
     * @param waveId   웨이브
     * @param campId   계획이 아직 없을 때 필요한 캠프
     * @param strategy 전략 이름(§6.6), 없으면 코어의 기본
     * @param mode     {@code FULL}·{@code FAST}(§6.7), 없으면 코어가 정한다
     */
    record RunPlan(UUID waveId, @Nullable UUID campId, @Nullable String strategy, @Nullable String mode)
            implements OpsCommand {
        public RunPlan {
            Objects.requireNonNull(waveId, "waveId");
        }

        @Override
        public String action() {
            return "RUN_PLAN";
        }

        @Override
        public String targetType() {
            return "WAVE";
        }

        @Override
        public UUID targetId() {
            return waveId;
        }

        @Override
        public Map<String, Object> arguments() {
            return present("waveId", waveId, "campId", campId, "strategy", strategy, "mode", mode);
        }
    }

    /**
     * stop 재배정 — dispatch {@code POST /routes/{routeId}/stops/{orderId}/reassign}.
     *
     * @param routeId       지금 라우트
     * @param orderId       옮길 주문
     * @param targetRouteId 받을 라우트
     */
    record ReassignStop(UUID routeId, UUID orderId, UUID targetRouteId) implements OpsCommand {
        public ReassignStop {
            Objects.requireNonNull(routeId, "routeId");
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(targetRouteId, "targetRouteId");
        }

        @Override
        public String action() {
            return "REASSIGN_STOP";
        }

        @Override
        public String targetType() {
            return "ORDER";
        }

        @Override
        public UUID targetId() {
            return orderId;
        }

        @Override
        public Map<String, Object> arguments() {
            return present("routeId", routeId, "orderId", orderId, "targetRouteId", targetRouteId);
        }
    }

    /**
     * 주문 취소 — order {@code POST /orders/{orderId}/cancel}.
     *
     * @param orderId 주문
     * @param reason  운영자가 적은 사유
     */
    record CancelOrder(UUID orderId, @Nullable String reason) implements OpsCommand {
        public CancelOrder {
            Objects.requireNonNull(orderId, "orderId");
        }

        @Override
        public String action() {
            return "CANCEL_ORDER";
        }

        @Override
        public String targetType() {
            return "ORDER";
        }

        @Override
        public UUID targetId() {
            return orderId;
        }

        @Override
        public Map<String, Object> arguments() {
            return present("orderId", orderId, "reason", reason);
        }
    }

    /** 이름·값 쌍에서 값이 있는 것만, 순서대로. */
    private static Map<String, Object> present(@Nullable Object... namesAndValues) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            Object value = namesAndValues[i + 1];
            if (value != null) {
                arguments.put(String.valueOf(namesAndValues[i]), value);
            }
        }
        return Collections.unmodifiableMap(arguments);
    }
}
