package com.dawnline.ops.adapter.in.web;

import com.dawnline.observability.MdcKeys;
import com.dawnline.ops.application.port.in.OpsCommand;
import com.dawnline.ops.application.port.in.RunOpsCommandUseCase;
import com.dawnline.ops.application.port.out.CoreReply;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자 커맨드 — 코어로 위임하고 {@code audit_logs} 에 남긴다 (DESIGN.md §5.5 「커맨드 위임」).
 *
 * <p>경로는 코어의 것을 그대로 쓴다 — ops-api 는 코어 앞에 선 유일한 표면이라 이름을 바꿀 이유가 없다.
 * 권한은 {@code SecurityConfig} 가 메서드로 정한다({@code POST} → {@code OPS_OPERATOR} 이상). 감사 행의
 * {@code actor} 는 토큰의 {@code sub} 다.
 *
 * <p>입력 검증은 코어의 계약보다 넓지 않게, 코어가 거절할 것을 미리 거절하는 정도만 한다 — 두 곳의 규칙이
 * 갈라지면 운영자는 어느 쪽이 거절했는지 모른다. 코어의 거절은 그 본문 그대로 돌아간다.
 */
@RestController
@RequestMapping(path = "/api/{version}", version = "1")
public class OpsCommandController {

    private final RunOpsCommandUseCase commands;

    public OpsCommandController(RunOpsCommandUseCase commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /**
     * 재계획 — dispatch 로 위임.
     *
     * @return 코어의 결과, 또는 {@link CommandResponses} 의 표
     */
    @PostMapping("/plans/{waveId}/run")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "재계획 결과",
                    headers = @Header(name = MdcKeys.AUDIT_ID_HEADER, description = "감사 행 id — 모든 결과에 온다"),
                    content = @Content(schema = @Schema(implementation = CoreReply.PlanRun.class))),
            @ApiResponse(responseCode = "400", description = "코어의 거절 그대로 — 인자가 틀렸다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "코어의 거절 그대로 — 없는 웨이브",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "코어의 거절 그대로 — 지금 계획할 수 없는 상태",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "`core-unreachable`(닿지 않았다 — 감사 `FAILED`) 또는 `core-error`(코어의 5xx — 감사 `UNKNOWN`)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "감사 행을 쓰지 못해 위임하지 않았다 — 기록 없는 커맨드는 없다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "504", description = "`core-timeout` — 적용됐는지 모른다(감사 `UNKNOWN`). 다시 누르기가 먼저다(RB-07)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<?> runPlan(@PathVariable UUID waveId,
            @RequestParam(required = false) @Nullable UUID campId,
            @RequestParam(required = false) @Nullable String strategy,
            @RequestParam(required = false) @Nullable String mode,
            @AuthenticationPrincipal Jwt operator, HttpServletRequest request) {
        return run(operator, new OpsCommand.RunPlan(waveId, campId, strategy, mode), request);
    }

    /**
     * stop 재배정 — dispatch 로 위임.
     *
     * @return 두 라우트의 새 revision, 또는 {@link CommandResponses} 의 표
     */
    @PostMapping("/routes/{routeId}/stops/{orderId}/reassign")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "두 라우트의 새 revision",
                    headers = @Header(name = MdcKeys.AUDIT_ID_HEADER, description = "감사 행 id — 모든 결과에 온다"),
                    content = @Content(schema = @Schema(implementation = CoreReply.StopReassigned.class))),
            @ApiResponse(responseCode = "400", description = "`targetRouteId` 가 없다(감사 행 없음), 또는 코어의 거절 그대로",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "코어의 거절 그대로 — 없는 라우트·주문",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "코어의 거절 그대로 — 옮길 수 없는 상태(출발한 라우트, 끝난 stop 등)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "`core-unreachable`(닿지 않았다 — 감사 `FAILED`) 또는 `core-error`(코어의 5xx — 감사 `UNKNOWN`)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "감사 행을 쓰지 못해 위임하지 않았다 — 기록 없는 커맨드는 없다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "504", description = "`core-timeout` — 적용됐는지 모른다(감사 `UNKNOWN`). 다시 누르기가 먼저다(RB-07)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<?> reassign(@PathVariable UUID routeId, @PathVariable UUID orderId,
            @Valid @RequestBody ReassignBody body,
            @AuthenticationPrincipal Jwt operator, HttpServletRequest request) {
        return run(operator, new OpsCommand.ReassignStop(routeId, orderId, Objects.requireNonNull(body.targetRouteId())),
                request);
    }

    /**
     * 주문 취소 — order 로 위임.
     *
     * @return 주문 id 와 상태, 또는 {@link CommandResponses} 의 표
     */
    @PostMapping("/orders/{orderId}/cancel")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "취소된 주문 — 주소는 싣지 않는다",
                    headers = @Header(name = MdcKeys.AUDIT_ID_HEADER, description = "감사 행 id — 모든 결과에 온다"),
                    content = @Content(schema = @Schema(implementation = CoreReply.OrderCancelled.class))),
            @ApiResponse(responseCode = "400", description = "`reason` 이 200자를 넘는다(감사 행 없음), 또는 코어의 거절 그대로",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "코어의 거절 그대로 — 없는 주문",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "코어의 거절 그대로 — 취소할 수 없는 상태",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "`core-unreachable`(닿지 않았다 — 감사 `FAILED`) 또는 `core-error`(코어의 5xx — 감사 `UNKNOWN`)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "감사 행을 쓰지 못해 위임하지 않았다 — 기록 없는 커맨드는 없다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "504", description = "`core-timeout` — 적용됐는지 모른다(감사 `UNKNOWN`). 다시 누르기가 먼저다(RB-07)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<?> cancel(@PathVariable UUID orderId,
            @Valid @RequestBody(required = false) @Nullable CancelBody body,
            @AuthenticationPrincipal Jwt operator, HttpServletRequest request) {
        return run(operator, new OpsCommand.CancelOrder(orderId, body == null ? null : body.reason()), request);
    }

    /**
     * 웨이브 조기 마감 — fulfillment 로 위임 (ADR-054). {@code reason} 은 필수이고, 공백·200자 초과는 코어에 가기 전에
     * 400 이며 감사 행을 남기지 않는다(재배정의 빈 본문과 같은 규칙).
     *
     * @return 마감된 웨이브, 또는 {@link CommandResponses} 의 표. 이미 닫힌 웨이브는 코어의 409 {@code wave-not-open}
     *         이 그대로 온다 — {@code closeCause} 가 감사 {@code UNKNOWN} 을 닫는 근거다(RB-07)
     */
    @PostMapping("/waves/{waveId}/close")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "닫힌 웨이브 — `closeCause=MANUAL`",
                    headers = @Header(name = MdcKeys.AUDIT_ID_HEADER, description = "감사 행 id — 모든 결과에 온다"),
                    content = @Content(schema = @Schema(implementation = CoreReply.WaveClosed.class))),
            @ApiResponse(responseCode = "400", description = "`reason` 이 비었거나 200자를 넘는다(감사 행 없음), 또는 코어의 거절 그대로",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "코어의 거절 그대로 — 없는 웨이브",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "코어의 거절 그대로 — `wave-not-open`(`closeCause` 가 `MANUAL` 이면 앞의 요청이 적용됐다, RB-07) "
                    + "또는 `not-next-wave`(같은 캠프 · 티어에 더 이른 열린 웨이브가 있다 — `earlierWaveId`)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "`core-unreachable`(닿지 않았다 — 감사 `FAILED`) 또는 `core-error`(코어의 5xx — 감사 `UNKNOWN`)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "감사 행을 쓰지 못해 위임하지 않았다 — 기록 없는 커맨드는 없다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "504", description = "`core-timeout` — 적용됐는지 모른다(감사 `UNKNOWN`). 다시 누르기가 먼저다(RB-07)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<?> closeWave(@PathVariable UUID waveId, @Valid @RequestBody CloseBody body,
            @AuthenticationPrincipal Jwt operator, HttpServletRequest request) {
        return run(operator, new OpsCommand.CloseWave(waveId, Objects.requireNonNull(body.reason())), request);
    }

    private ResponseEntity<?> run(Jwt operator, OpsCommand command, HttpServletRequest request) {
        return CommandResponses.of(commands.run(operator.getSubject(), command), request.getRequestURI());
    }

    /**
     * @param targetRouteId 받을 라우트
     */
    public record ReassignBody(@NotNull @Nullable UUID targetRouteId) {
    }

    /**
     * @param reason 사유 — order 의 계약과 같은 상한(200자)
     */
    public record CancelBody(@Size(max = 200) @Nullable String reason) {
    }

    /**
     * @param reason 왜 컷오프를 앞당기는가 — fulfillment 의 계약({@code CloseWaveRequest})과 같다: 필수, 공백 불가, 200자
     */
    public record CloseBody(@NotBlank @Size(max = 200) @Nullable String reason) {
    }
}
