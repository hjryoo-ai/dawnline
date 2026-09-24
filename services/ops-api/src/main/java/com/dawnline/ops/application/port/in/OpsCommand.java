package com.dawnline.ops.application.port.in;

import com.dawnline.ops.domain.CoreService;
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

    /**
     * 웨이브 조기 마감 — fulfillment {@code POST /waves/{waveId}/close} (ADR-054).
     *
     * @param waveId 웨이브
     * @param reason 왜 컷오프를 앞당기는가 — 필수다(ADR-054 결정 2). 남은 시간 동안의 접수가 전부 약속을 받자마자
     *               개정되는 결정이라 감사 행에 「왜」가 있어야 한다
     */
    record CloseWave(UUID waveId, String reason) implements OpsCommand {
        public CloseWave {
            Objects.requireNonNull(waveId, "waveId");
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String action() {
            return "CLOSE_WAVE";
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
            return present("waveId", waveId, "reason", reason);
        }
    }

    /**
     * outbox 격리 행 재큐 — {@code {service}} 코어의 {@code POST /admin/outbox/{id}/requeue} (§4.6).
     *
     * @param service 어느 코어의 행인가
     * @param id      행 id({@code eventId})
     */
    record RequeueOutbox(CoreService service, UUID id) implements OpsCommand {
        public RequeueOutbox {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(id, "id");
        }

        @Override
        public String action() {
            return "REQUEUE_OUTBOX";
        }

        @Override
        public String targetType() {
            return "OUTBOX_EVENT";
        }

        @Override
        public UUID targetId() {
            return id;
        }

        /** {@code service} 가 먼저다 — 같은 id 가 다른 코어에 있을 수는 없지만, 사람이 볼 곳은 그 코어다. */
        @Override
        public Map<String, Object> arguments() {
            return present("service", service.path(), "id", id);
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
