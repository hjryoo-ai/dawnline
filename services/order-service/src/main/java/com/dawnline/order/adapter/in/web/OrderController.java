package com.dawnline.order.adapter.in.web;

import com.dawnline.order.application.port.in.CancelOrderUseCase;
import com.dawnline.order.application.port.in.GetOrderUseCase;
import com.dawnline.order.application.port.in.ListOrdersQuery;
import com.dawnline.order.application.port.in.ListOrdersUseCase;
import com.dawnline.order.application.port.in.OrderCursor;
import com.dawnline.order.application.port.in.OrderView;
import com.dawnline.order.application.port.in.PlaceOrderResult;
import com.dawnline.order.application.port.in.PlaceOrderUseCase;
import com.dawnline.order.application.port.out.RateLimiter;
import com.dawnline.order.domain.OrderErrorCode;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.common.error.DomainException;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 주문 API (DESIGN.md §5.1).
 *
 * <h2>인증이 없다</h2>
 * 의도된 결정이다(§10, §17-2). 그 대가도 분명하다 — {@code customerId} 는 클라이언트 주장값이고,
 * 주문 id 를 아는 사람은 누구나 그 주문을 읽고 취소할 수 있다. 주문 id 가 UUIDv7(무작위 74비트)
 * 이라는 것이 사실상 유일한 방어이며, 실서비스 전환 시 인증과 함께 재검토한다.
 * 그래서 목록 조회는 {@code customerId} 를 <em>필수</em>로 받는다 — 인증이 없으므로 "내 주문" 을
 * 서버가 알아낼 방법이 없다.
 *
 * <h2>버전</h2>
 * 경로 세그먼트가 버전이며, Spring Framework 7 의 API 버저닝이 그 세그먼트를 읽는다(ADR-009).
 * 매핑 경로에 {@code v1} 을 <strong>박아 넣지 않고</strong> {@code {version}} 으로 두는 이유:
 * 리터럴로 두면 {@code /api/v2/orders} 가 경로 매칭에서 먼저 떨어져 <em>404</em> 가 된다.
 * 사실은 "그런 리소스가 없다" 가 아니라 "그 버전은 지원하지 않는다" 이고, 자리표시자로 두어야
 * 버전 조건까지 도달해 그렇게 답할 수 있다. 실제로 확인한 동작이다.
 */
@RestController
@RequestMapping(path = "/api/{version}/orders", version = "1")
public class OrderController {

    private final PlaceOrderUseCase placeOrder;
    private final GetOrderUseCase getOrder;
    private final ListOrdersUseCase listOrders;
    private final CancelOrderUseCase cancelOrder;
    private final Optional<RateLimiter> rateLimiter;

    /**
     * @param placeOrder  주문 접수
     * @param getOrder    주문 상세
     * @param listOrders  주문 목록
     * @param cancelOrder 주문 취소
     * @param rateLimiter 고객별 레이트 리밋 (§7.2). 꺼 두면 비어 있다
     */
    public OrderController(PlaceOrderUseCase placeOrder, GetOrderUseCase getOrder,
            ListOrdersUseCase listOrders, CancelOrderUseCase cancelOrder,
            Optional<RateLimiter> rateLimiter) {
        this.placeOrder = Objects.requireNonNull(placeOrder, "placeOrder");
        this.getOrder = Objects.requireNonNull(getOrder, "getOrder");
        this.listOrders = Objects.requireNonNull(listOrders, "listOrders");
        this.cancelOrder = Objects.requireNonNull(cancelOrder, "cancelOrder");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
    }

    /**
     * 주문 접수. 새 주문이면 201, 같은 멱등 키의 재요청이면 저장된 응답을 200 으로 재생한다(§5.1).
     *
     * <p>201 에는 {@code Location} 을 붙인다. 200(재생)에는 붙이지 않는다 — 새로 만들어진 것이
     * 없기 때문이고, 그 차이가 두 응답을 구분하는 또 하나의 신호가 된다.
     *
     * @param idempotencyKey {@code Idempotency-Key} 헤더 (필수)
     * @param request        요청 본문
     */
    @PostMapping
    public ResponseEntity<Object> place(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {

        checkRateLimit(request.customerId());

        PlaceOrderResult result = placeOrder.place(request.toCommand(idempotencyKey));
        if (result.replayed()) {
            return ResponseEntity.ok(result.order());
        }
        return ResponseEntity.created(URI.create("/api/v1/orders/" + result.order().orderId()))
                .body(result.order());
    }

    /**
     * 레이트 리밋 (§7.2, §8.3). 쓰기 경로 중 <strong>가장 앞</strong>이다 — 이 뒤로는 지오코딩·DB·
     * Redis 가 이어지므로, 막을 것은 그 전에 막아야 의미가 있다.
     *
     * <p>Bean Validation 뒤에 서는 것은 어쩔 수 없다. 판정에 필요한 {@code customerId} 가 본문에
     * 있고(무인증이라 헤더에서 얻을 수 없다, §10), 본문을 읽으려면 바인딩이 끝나 있어야 한다.
     * 형식이 틀린 요청은 어차피 DB·Redis 를 건드리지 않고 400 으로 끝난다.
     *
     * <p>Redis 장애로 판정을 건너뛴 경우({@code BYPASSED})도 통과시킨다(§7.2 fail-open).
     * 그 상태는 {@code dawnline_rate_limit_decisions_total{outcome="bypassed"}} 로 보이고
     * §9.4 가 알림을 건다 — 무인증 API 의 유일한 남용 방지 수단이 꺼진 상태이기 때문이다.
     */
    private void checkRateLimit(UUID customerId) {
        if (rateLimiter.isEmpty()) {
            return;
        }
        RateLimiter.Decision decision = rateLimiter.get().tryAcquire(customerId);
        if (decision.isAllowed()) {
            return;
        }
        throw new DomainException(OrderErrorCode.RATE_LIMITED,
                "요청이 너무 잦습니다. 잠시 후 다시 시도하세요.",
                Map.of(ProblemDetailsAdvice.RETRY_AFTER_DETAIL, decision.retryAfterSeconds()));
    }

    /**
     * 주문 상세.
     *
     * @param orderId 주문 id
     */
    @GetMapping("/{orderId}")
    public OrderView get(@PathVariable UUID orderId) {
        return getOrder.get(orderId);
    }

    /**
     * 주문 취소. {@code PLACED}·{@code PLANNED} 에서만 허용되며 그 밖은 409 다(§5.1).
     *
     * @param orderId 주문 id
     * @param request 취소 사유. 본문 없이 보내도 된다
     */
    @PostMapping("/{orderId}/cancel")
    public OrderView cancel(@PathVariable UUID orderId,
            @Valid @RequestBody(required = false) @Nullable CancelOrderRequest request) {
        return cancelOrder.cancel(orderId, request == null ? null : request.reason());
    }

    /**
     * 주문 목록. 접수 시각 내림차순, 커서 페이지네이션이다.
     *
     * @param customerId 고객 id (필수 — 인증이 없어 서버가 "내 주문" 을 알 수 없다)
     * @param status     상태 필터
     * @param from       접수 시각 하한(포함)
     * @param to         접수 시각 상한(제외)
     * @param cursor     이전 응답의 {@code nextCursor}
     * @param limit      한 페이지 건수
     */
    @GetMapping
    public OrderPageResponse list(
            @RequestParam UUID customerId,
            @RequestParam(required = false) @Nullable OrderStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @Nullable Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @Nullable Instant to,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "" + ListOrdersQuery.DEFAULT_LIMIT) int limit) {

        OrderCursor decoded = cursor == null ? null : Cursors.decode(cursor);
        ListOrdersQuery query = new ListOrdersQuery(customerId, status, from, to,
                decoded == null ? null : decoded.placedAt(),
                decoded == null ? null : decoded.orderId(),
                limit);
        return OrderPageResponse.of(listOrders.list(query));
    }
}
