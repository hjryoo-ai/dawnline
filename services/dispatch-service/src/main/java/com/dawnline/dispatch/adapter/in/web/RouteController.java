package com.dawnline.dispatch.adapter.in.web;

import com.dawnline.common.error.NotFoundException;
import com.dawnline.dispatch.application.port.in.ReassignStopUseCase;
import com.dawnline.dispatch.application.port.in.RouteView;
import com.dawnline.dispatch.application.port.out.PlanQueries;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 라우트 API (DESIGN.md §5.3). 운영자용이다.
 *
 * <h2>버전</h2>
 * 매핑 경로에 {@code v1} 을 <strong>박아 넣지 않고</strong> {@code {version}} 으로 둔다
 * (ADR-009 결정 2). 리터럴로 두면 {@code /api/v2/...} 가 경로 매칭에서 먼저 떨어져 <em>404</em>
 * 가 되어, 「그런 리소스가 없다」와 「그 버전은 지원하지 않는다」가 구분되지 않는다.
 * 쓰이지 않는 자리표시자처럼 보여 지우고 싶어지는 코드이고, 지우면 그 구분이 조용히 사라진다 —
 * ArchUnit 규칙 8 이 그것을 막는다.
 */
@RestController
@RequestMapping(path = "/api/{version}/routes", version = "1")
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
            // 오류 본문의 스키마를 명시한다. 적지 않으면 springdoc 이 <메서드 반환 타입>을 모든
            // 응답에 붙여 문서가 「404 의 본문은 RouteView」라고 말하게 된다 (§11, ADR-044 와 같은 부류).
            @ApiResponse(responseCode = "400", description = "`routeId` 가 UUID 형식이 아니다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "없는 라우트",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
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
            @ApiResponse(responseCode = "200", description = "옮겼다. 두 라우트의 새 revision 이 온다"),
            @ApiResponse(responseCode = "400", description = "`targetRouteId` 가 없거나 UUID 형식이 아니다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "없는 라우트·주문",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409",
                    description = "옮기면 하드 룰을 어긴다. 재시도해도 결과가 같아 `Retry-After` 는 없다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
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
