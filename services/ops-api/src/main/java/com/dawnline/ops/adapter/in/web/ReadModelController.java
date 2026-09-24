package com.dawnline.ops.adapter.in.web;

import com.dawnline.ops.application.port.in.QueryReadModelUseCase;
import com.dawnline.ops.application.port.in.QueryReadModelUseCase.CampList;
import com.dawnline.ops.application.port.in.QueryReadModelUseCase.DeliveryKpi;
import com.dawnline.ops.application.port.in.QueryReadModelUseCase.ExceptionList;
import com.dawnline.ops.application.port.in.QueryReadModelUseCase.WaveList;
import com.dawnline.ops.application.port.in.QueryReadModelUseCase.WaveRoutes;
import com.dawnline.ops.application.port.out.CoreReply;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 캠프 대시보드와 라우트 지도의 조회 (DESIGN.md §5.5 「조회」).
 *
 * <p>전부 {@code GET} 이라 {@code OPS_VIEWER} 에게 열리고({@code SecurityConfig}) 감사하지 않는다. 읽기 모델에서 읽고,
 * 라우트의 stop 만 dispatch 에 조회를 위임한다 — 좌표와 순서의 진실은 dispatch 이고 읽기 모델은 집계다.
 */
@RestController
@RequestMapping(path = "/api/{version}", version = "1")
public class ReadModelController {

    private final QueryReadModelUseCase queries;

    public ReadModelController(QueryReadModelUseCase queries) {
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    /** @return 웨이브가 하나라도 있는 캠프들 */
    @GetMapping("/camps")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "캠프 id 순 — 읽기 모델에 웨이브가 있는 캠프만"))
    public CampList camps() {
        return queries.camps();
    }

    /**
     * @param campId 캠프
     * @param from   컷오프 창의 시작(포함), 없으면 지금 − 24시간
     * @param to     컷오프 창의 끝(제외), 없으면 지금 + 24시간
     * @return 컷오프 순
     */
    @GetMapping("/camps/{campId}/waves")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "컷오프 순, 최대 500. 칸이 `null` 이면 그 사실이 아직 오지 않았다"),
            @ApiResponse(responseCode = "400", description = "창이 뒤집혔거나 7일을 넘는다, 또는 형식이 틀렸다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public WaveList waves(@PathVariable UUID campId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant to) {
        return queries.waves(campId, from, to);
    }

    /** @return 정시율 두 기준 — 게이지와 같은 뷰·창·식 */
    @GetMapping("/kpi/delivery")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "현재 버킷 포함 UTC 정시 버킷 24개. 게이지 "
            + "`dawnline_delivery_on_time_ratio` 와 같은 뷰·창·식이다. 정시율이 `null` 이면 창 안에 결과가 없다"))
    public DeliveryKpi deliveryKpi() {
        return queries.deliveryKpi();
    }

    /**
     * @param campId 캠프
     * @return 취소됐는데 배송된 주문
     */
    @GetMapping("/camps/{campId}/exceptions")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "취소됐는데 배송된 주문(§6.10 넷째 분기) — **창 없이 "
            + "전부**, 배송 시각 역순으로 앞 200 행과 전체 수(`total`). 자동 보상은 없고 사람이 처리한다. **해소 여부는 "
            + "이 시스템이 모른다** — 환불·회수를 기록하는 칸이 없으므로 한 번 들어온 주문은 목록에서 나가지 않는다"))
    public ExceptionList exceptions(@PathVariable UUID campId) {
        return queries.exceptions(campId);
    }

    /**
     * @param waveId 웨이브
     * @return 계획의 라우트들과 창고
     */
    @GetMapping("/waves/{waveId}/routes")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "계획의 라우트들과 창고 좌표. 계획이 아직 없으면 라우트가 비어 있고, "
                    + "`depot` 이 `null` 이면 지도는 stop 들의 중심으로 물러난다"),
            @ApiResponse(responseCode = "404", description = "읽기 모델에 없는 웨이브",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public WaveRoutes waveRoutes(@PathVariable UUID waveId) {
        return queries.waveRoutes(waveId);
    }

    /**
     * @param routeId 라우트
     * @return dispatch 의 라우트, 또는 코어의 거절 그대로, 또는 502·504
     */
    @GetMapping("/routes/{routeId}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "stop 순서·좌표·상태 — dispatch 에 조회를 위임한다(감사 없음)",
                    content = @Content(schema = @Schema(implementation = CoreReply.RouteDetail.class))),
            @ApiResponse(responseCode = "404", description = "dispatch 에 없는 라우트 — 코어의 본문 그대로",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "`core-unreachable` 또는 `core-error`",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "504", description = "`core-timeout`",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<?> route(@PathVariable UUID routeId, HttpServletRequest request) {
        return CommandResponses.ofQuery(queries.route(routeId), request.getRequestURI());
    }
}
