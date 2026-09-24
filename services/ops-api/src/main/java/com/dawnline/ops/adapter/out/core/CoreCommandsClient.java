package com.dawnline.ops.adapter.out.core;

import com.dawnline.ops.adapter.out.core.dispatch.api.PlanControllerApi;
import com.dawnline.ops.adapter.out.core.dispatch.api.RouteControllerApi;
import com.dawnline.ops.adapter.out.core.dispatch.model.ReassignRequest;
import com.dawnline.ops.adapter.out.core.dispatch.model.Result;
import com.dawnline.ops.adapter.out.core.dispatch.model.RunPlanResponse;
import com.dawnline.ops.adapter.out.core.order.api.OrderControllerApi;
import com.dawnline.ops.adapter.out.core.order.model.CancelOrderRequest;
import com.dawnline.ops.adapter.out.core.order.model.OrderView;
import com.dawnline.ops.application.port.in.OpsCommand;
import com.dawnline.ops.application.port.out.CoreCommands;
import com.dawnline.ops.application.port.out.CoreReply;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
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
 *   <li>4xx → {@link CoreReply.Rejected}. 본문은 바이트 그대로 — 계약 문서의 {@code ProblemDetail} 스키마는
 *       확장 멤버를 중첩 객체로 적지만 실제 본문은 최상위로 펼친다(ADR-052 「문서의 거짓 하나」).</li>
 *   <li>연결이 맺어지지 않았다({@link ConnectException}·{@link HttpConnectTimeoutException}·
 *       {@link UnknownHostException}) → {@link CoreReply.Unreachable}. 요청이 나가지 않았다는 것을 아는
 *       유일한 경우다.</li>
 *   <li>그 밖의 전부 → {@link CoreReply.Unknown}: 응답 전 타임아웃, 응답 도중 끊김, 5xx, 본문을 읽지 못함.</li>
 * </ul>
 */
public class CoreCommandsClient implements CoreCommands {

    private final PlanControllerApi plans;
    private final RouteControllerApi routes;
    private final OrderControllerApi orders;

    /**
     * @param plans  dispatch 계획
     * @param routes dispatch 라우트
     * @param orders order 주문
     */
    public CoreCommandsClient(PlanControllerApi plans, RouteControllerApi routes, OrderControllerApi orders) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.orders = Objects.requireNonNull(orders, "orders");
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
        };
    }

    private static CoreReply call(UUID auditId, Supplier<CoreReply.Applied.Body> call) {
        try {
            return new CoreReply.Applied(AuditIdPropagation.with(auditId, call));
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
