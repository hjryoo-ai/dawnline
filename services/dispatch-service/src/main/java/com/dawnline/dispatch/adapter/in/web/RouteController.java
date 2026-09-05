package com.dawnline.dispatch.adapter.in.web;

import com.dawnline.common.error.NotFoundException;
import com.dawnline.dispatch.application.port.in.ReassignStopUseCase;
import com.dawnline.dispatch.application.port.in.RouteView;
import com.dawnline.dispatch.application.port.out.PlanQueries;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 라우트 API (DESIGN.md §5.3). 운영자용이다. */
@RestController
@RequestMapping("/api/v1/routes")
public class RouteController {

    private final PlanQueries queries;
    private final ReassignStopUseCase reassign;

    /**
     * @param queries  조회
     * @param reassign stop 이동
     */
    public RouteController(PlanQueries queries, ReassignStopUseCase reassign) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.reassign = Objects.requireNonNull(reassign, "reassign");
    }

    /**
     * 라우트·stop 목록.
     *
     * @param routeId 라우트 id
     */
    @GetMapping("/{routeId}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "라우트 상세"),
            @ApiResponse(responseCode = "404", description = "없는 라우트")})
    public RouteView get(@PathVariable UUID routeId) {
        return queries.findRoute(routeId)
                .orElseThrow(() -> NotFoundException.of("Route", routeId.toString()));
    }

    /**
     * stop 을 다른 라우트로 옮긴다 (운영자).
     *
     * <p>두 라우트 모두 {@code revision} 이 오르고 {@code route.assigned} 가 다시 나간다 —
     * 소비자는 자신이 이미 본 revision 이하를 무시하므로(§6.8 4단계) 순서가 뒤바뀌어도 안전하다.
     *
     * @param routeId 현재 라우트
     * @param orderId 옮길 주문
     * @param request 목적지
     */
    @PostMapping("/{routeId}/stops/{orderId}/reassign")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "옮겼다"),
            @ApiResponse(responseCode = "404", description = "없는 라우트·주문"),
            @ApiResponse(responseCode = "409", description = "옮기면 하드 룰을 어긴다")})
    public ReassignStopUseCase.Result reassign(@PathVariable UUID routeId,
            @PathVariable UUID orderId, @Valid @RequestBody ReassignRequest request) {

        return reassign.reassign(routeId, orderId, request.targetRouteId());
    }

    /**
     * 이동 요청.
     *
     * @param targetRouteId 옮겨 갈 라우트
     */
    public record ReassignRequest(@NotNull UUID targetRouteId) {
    }
}
