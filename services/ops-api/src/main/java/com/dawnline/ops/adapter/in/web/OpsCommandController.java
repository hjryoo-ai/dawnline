package com.dawnline.ops.adapter.in.web;

import com.dawnline.ops.application.port.in.OpsCommand;
import com.dawnline.ops.application.port.in.RunOpsCommandUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
