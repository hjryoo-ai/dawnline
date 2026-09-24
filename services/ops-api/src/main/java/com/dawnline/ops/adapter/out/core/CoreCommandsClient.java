package com.dawnline.ops.adapter.out.core;

import com.dawnline.ops.adapter.out.core.dispatch.api.PlanControllerApi;
import com.dawnline.ops.adapter.out.core.dispatch.api.RouteControllerApi;
import com.dawnline.ops.adapter.out.core.dispatch.model.ReassignRequest;
import com.dawnline.ops.adapter.out.core.dispatch.model.Result;
import com.dawnline.ops.adapter.out.core.dispatch.model.RunPlanResponse;
import com.dawnline.ops.adapter.out.core.fulfillment.api.WaveControllerApi;
import com.dawnline.ops.adapter.out.core.fulfillment.model.CloseWaveRequest;
import com.dawnline.ops.adapter.out.core.fulfillment.model.WaveView;
import com.dawnline.ops.adapter.out.core.order.api.OrderControllerApi;
import com.dawnline.ops.adapter.out.core.order.model.CancelOrderRequest;
import com.dawnline.ops.adapter.out.core.order.model.OrderView;
import com.dawnline.ops.application.port.in.OpsCommand;
import com.dawnline.ops.application.port.out.CoreCommands;
import com.dawnline.ops.application.port.out.CoreQueries;
import com.dawnline.ops.application.port.out.CoreReply;
import com.dawnline.ops.domain.CoreService;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * 코어 위임 — 생성된 클라이언트를 부르고 답을 {@link CoreReply} 의 갈래로 옮긴다 (ADR-052).
 *
 * <p>인터페이스({@code *ControllerApi})와 모델은 커밋된 {@code contracts/openapi/*.yaml} 에서 빌드 때
 * 생성된다. 이 클래스가 쓰는 연산·칸의 이름이 문서에서 바뀌면 <strong>여기서 컴파일이 깨진다</strong> —
 * 그것이 「문서가 계약이다」의 검사다.
 *
 * <h2>갈래를 나누는 기준 — 코어에 닿았는가를 아는가</h2>
 * <ul>
 *   <li>4xx → {@link CoreReply.Rejected}. 본문은 바이트 그대로 — ops-api 는 이 자리에서 프록시다. 운영자는
 *       코어가 말한 것을 그대로 보고, 코어가 나중에 확장 멤버를 더해도 여기서 잘리지 않는다. 문서의
 *       {@code ProblemDetail} 은 실제 본문과 같다(ADR-052) — 읽을 수 있지만 읽을 필요가 없다.</li>
 *   <li>연결이 맺어지지 않았다({@link ConnectException}·{@link HttpConnectTimeoutException}·
 *       {@link UnknownHostException}) → {@link CoreReply.Unreachable}. 요청이 나가지 않았다는 것을 아는
 *       유일한 경우다.</li>
 *   <li>그 밖의 전부 → {@link CoreReply.Unknown}: 응답 전 타임아웃, 응답 도중 끊김, 5xx, 본문을 읽지 못함.</li>
 * </ul>
 */
public class CoreCommandsClient implements CoreCommands, CoreQueries {

    private final PlanControllerApi plans;
    private final RouteControllerApi routes;
    private final OrderControllerApi orders;
    private final WaveControllerApi waves;
    private final Map<CoreService, OutboxAdminClients.OutboxAdmin> outbox;

    /**
     * @param plans  dispatch 계획
     * @param routes dispatch 라우트
     * @param orders order 주문
     * @param waves  fulfillment 웨이브
     * @param outbox 코어 넷의 outbox 관리 — {@link CoreService} 넷이 전부 있어야 한다
     */
    CoreCommandsClient(PlanControllerApi plans, RouteControllerApi routes, OrderControllerApi orders,
            WaveControllerApi waves, Map<CoreService, OutboxAdminClients.OutboxAdmin> outbox) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.waves = Objects.requireNonNull(waves, "waves");
        this.outbox = new EnumMap<>(outbox);
        if (!this.outbox.keySet().equals(EnumSet.allOf(CoreService.class))) {
            throw new IllegalArgumentException("outbox 관리 클라이언트가 코어 넷에 하나씩 있어야 한다: " + this.outbox.keySet());
        }
    }

    /**
     * 생성된 클라이언트 전부로 만든다 (CoreClientsConfig).
     *
     * @return 위임과 조회
     */
    public static CoreCommandsClient of(PlanControllerApi plans, RouteControllerApi routes, OrderControllerApi orders,
            WaveControllerApi waves,
            com.dawnline.ops.adapter.out.core.order.api.OutboxAdminControllerApi orderOutbox,
            com.dawnline.ops.adapter.out.core.fulfillment.api.OutboxAdminControllerApi fulfillmentOutbox,
            com.dawnline.ops.adapter.out.core.dispatch.api.OutboxAdminControllerApi dispatchOutbox,
            com.dawnline.ops.adapter.out.core.tracking.api.OutboxAdminControllerApi trackingOutbox) {
        return new CoreCommandsClient(plans, routes, orders, waves, Map.of(
                CoreService.ORDER, OutboxAdminClients.order(orderOutbox),
                CoreService.FULFILLMENT, OutboxAdminClients.fulfillment(fulfillmentOutbox),
                CoreService.DISPATCH, OutboxAdminClients.dispatch(dispatchOutbox),
                CoreService.TRACKING, OutboxAdminClients.tracking(trackingOutbox)));
    }

    @Override
    public CoreReply delegate(UUID auditId, OpsCommand command) {
        return switch (command) {
            case OpsCommand.RunPlan run -> call(auditId, () -> planRun(
                    plans.run(run.waveId(), run.campId(), run.strategy(), run.mode())));
            case OpsCommand.ReassignStop reassign -> call(auditId, () -> reassigned(
                    routes.reassign(reassign.routeId(), reassign.orderId(), new ReassignRequest(reassign.targetRouteId()))));
            case OpsCommand.CancelOrder cancel -> call(auditId, () -> cancelled(
                    orders.cancel(cancel.orderId(), new CancelOrderRequest().reason(cancel.reason()))));
            case OpsCommand.CloseWave close -> call(auditId, () -> closed(
                    waves.close(close.waveId(), new CloseWaveRequest(close.reason()))));
            case OpsCommand.RequeueOutbox requeue -> call(auditId, () -> outbox.get(requeue.service())
                    .requeue(requeue.id()));
        };
    }

    /** 조회 — 감사 id 가 없으므로 상관 헤더도 없다. 갈래를 나누는 규칙은 위임과 같다. */
    @Override
    public CoreReply listQuarantined(CoreService service, @Nullable Integer limit) {
        return answer(() -> outbox.get(service).list(limit));
    }

    private static CoreReply call(UUID auditId, Supplier<CoreReply.Applied.Body> call) {
        return answer(() -> AuditIdPropagation.with(auditId, call));
    }

    private static CoreReply answer(Supplier<CoreReply.Applied.Body> call) {
        try {
            return new CoreReply.Applied(call.get());
        } catch (HttpClientErrorException e) {
            MediaType type = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getContentType();
            return new CoreReply.Rejected(e.getStatusCode().value(),
                    e.getResponseBodyAsString(StandardCharsets.UTF_8), type == null ? null : type.toString());
        } catch (HttpServerErrorException e) {
            return new CoreReply.Unknown(false, e.getStatusCode().value(), e.getMessage());
        } catch (ResourceAccessException e) {
            return notAnswered(e);
        } catch (RestClientException e) {
            return new CoreReply.Unknown(false, null, e.toString());
        }
    }

    /** I/O 실패를 원인으로 가른다. 연결이 맺어지지 않은 것만 「닿지 않았다」이다. */
    private static CoreReply notAnswered(ResourceAccessException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException
                    || cause instanceof UnknownHostException) {
                return new CoreReply.Unreachable(cause.toString());
            }
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return new CoreReply.Unknown(true, null, cause.toString());
            }
        }
        return new CoreReply.Unknown(false, null, e.getCause() instanceof IOException io ? io.toString() : e.toString());
    }

    private static CoreReply.PlanRun planRun(ResponseEntity<RunPlanResponse> response) {
        RunPlanResponse body = required(response.getBody());
        return new CoreReply.PlanRun(required(body.getWaveId()), required(body.getOutcome()));
    }

    private static CoreReply.StopReassigned reassigned(ResponseEntity<Result> response) {
        Result body = required(response.getBody());
        return new CoreReply.StopReassigned(required(body.getOrderId()), required(body.getFromRouteId()),
                required(body.getFromRevision()), required(body.getToRouteId()), required(body.getToRevision()));
    }

    private static CoreReply.WaveClosed closed(ResponseEntity<WaveView> response) {
        WaveView body = required(response.getBody());
        return new CoreReply.WaveClosed(required(body.getWaveId()), required(body.getStatus()).getValue(),
                body.getCloseCause() == null ? null : body.getCloseCause().getValue(),
                body.getClosedAt() == null ? null : body.getClosedAt().toInstant());
    }

    private static CoreReply.OrderCancelled cancelled(ResponseEntity<OrderView> response) {
        OrderView body = required(response.getBody());
        return new CoreReply.OrderCancelled(required(body.getOrderId()), required(body.getStatus()).getValue());
    }

    /** 2xx 인데 칸이 비었다 — 적용은 됐지만 무엇이 됐는지 모른다. 호출자가 {@code UNKNOWN} 으로 접는다. */
    private static <T> T required(@Nullable T value) {
        if (value == null) {
            throw new RestClientException("코어의 2xx 본문에 계약이 말한 칸이 없다");
        }
        return value;
    }
}
