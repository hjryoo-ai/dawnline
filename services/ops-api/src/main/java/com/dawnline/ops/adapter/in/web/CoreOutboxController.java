package com.dawnline.ops.adapter.in.web;

import com.dawnline.common.error.NotFoundException;
import com.dawnline.ops.application.port.in.ListQuarantinedOutboxUseCase;
import com.dawnline.ops.application.port.in.OpsCommand;
import com.dawnline.ops.application.port.in.RunOpsCommandUseCase;
import com.dawnline.ops.domain.CoreService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 코어 넷의 outbox 격리 조회·재큐 (DESIGN.md §4.6 · §5.5 「커맨드 위임」).
 *
 * <p>코어의 경로에 {@code {service}} 한 칸이 붙는다 — 같은 공유 코드가 코어 넷에 같은 경로를 만들었으므로 어느 코어의
 * 것인지를 경로가 말한다. 값은 {@link CoreService} 넷이고 <strong>그 밖의 값은 404 이며 감사 행을 남기지 않는다</strong>
 * — 커맨드가 아니라 없는 경로다. 판정은 감사 행을 쓰기 전에 한다.
 *
 * <p>목록은 조회라 감사하지 않고 {@code OPS_VIEWER} 에게 열린다({@code SecurityConfig} — {@code GET} 은 뷰어).
 * 재큐는 커맨드다: 감사 {@code REQUEUE_OUTBOX} · {@code OUTBOX_EVENT}.
 */
@RestController
@RequestMapping(path = "/api/{version}/admin/outbox/{service}", version = "1")
public class CoreOutboxController {

    private final ListQuarantinedOutboxUseCase quarantined;
    private final RunOpsCommandUseCase commands;

    public CoreOutboxController(ListQuarantinedOutboxUseCase quarantined, RunOpsCommandUseCase commands) {
        this.quarantined = Objects.requireNonNull(quarantined, "quarantined");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /**
     * 격리 목록 — 코어의 본문을 옮긴다. {@code limit} 의 범위(1–500)는 코어가 본다.
     *
     * @return 목록, 또는 코어의 거절 그대로, 또는 502·504
     */
    @GetMapping("/quarantined")
    public ResponseEntity<?> listQuarantined(@PathVariable String service,
            @RequestParam(required = false) @Nullable Integer limit, HttpServletRequest request) {
        return CommandResponses.ofQuery(quarantined.list(core(service), limit), request.getRequestURI());
    }

    /**
     * 재큐 — {@code {service}} 코어로 위임.
     *
     * @return 풀린 행, 또는 {@link CommandResponses} 의 표. 이미 풀린 행은 코어의 409 {@code not-quarantined} 가 그대로
     *         온다 — {@code currentState} 가 감사 {@code UNKNOWN} 을 닫는 근거다(RB-07)
     */
    @PostMapping("/{id}/requeue")
    public ResponseEntity<?> requeue(@PathVariable String service, @PathVariable UUID id,
            @AuthenticationPrincipal Jwt operator, HttpServletRequest request) {
        OpsCommand command = new OpsCommand.RequeueOutbox(core(service), id);
        return CommandResponses.of(commands.run(operator.getSubject(), command), request.getRequestURI());
    }

    private static CoreService core(String service) {
        return CoreService.fromPath(service).orElseThrow(() -> new NotFoundException(
                "outbox 관리 경로가 없는 서비스다 — order·fulfillment·dispatch·tracking 중 하나", Map.of("service", service)));
    }
}
