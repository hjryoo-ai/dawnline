package com.dawnline.ops.adapter.in.web;

import com.dawnline.observability.MdcKeys;
import com.dawnline.ops.application.port.in.ResolveAuditUseCase;
import com.dawnline.ops.domain.AuditResolution;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 감사 해소 — 대상 행은 고치지 않고 해소 행을 더한다 (DESIGN.md §5.5 「감사 해소」, ADR-065).
 *
 * <p>위임이 없는 커맨드다 — 코어를 부르지 않는다. 권한은 다른 커맨드와 같다({@code POST} → {@code OPS_OPERATOR} 이상,
 * {@code SecurityConfig}).
 */
@RestController
@RequestMapping(path = "/api/{version}", version = "1")
public class AuditController {

    private final ResolveAuditUseCase resolve;

    public AuditController(ResolveAuditUseCase resolve) {
        this.resolve = Objects.requireNonNull(resolve, "resolve");
    }

    /**
     * 감사 {@code UNKNOWN} · 5분 넘은 {@code PENDING} 을 닫는다.
     *
     * @return 적힌 해소 행 — 헤더 {@code X-Dawnline-Audit-Id} 는 <strong>새 행</strong>의 id
     */
    @PostMapping("/audit/{auditId}/resolve")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "해소 행이 적혔다 — 대상 행은 그대로다",
                    headers = @Header(name = MdcKeys.AUDIT_ID_HEADER, description = "해소 행의 id"),
                    content = @Content(schema = @Schema(implementation = ResolvedView.class))),
            @ApiResponse(responseCode = "400", description = "`resolution` 이 없거나 `reason` 이 비었거나 200자를 넘는다 — 행을 남기지 않는다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "그런 감사 행이 없다 — `REJECTED` 해소 행이 남는다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "`audit-already-resolved`(`resolutionId` — 해소는 한 번이다) 또는 "
                    + "`audit-not-resolvable`(`currentResult` — 대상이 `UNKNOWN` 도 5분 넘은 `PENDING` 도 아니다). `REJECTED` 해소 행이 남는다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<ResolvedView> resolveAudit(@PathVariable UUID auditId, @Valid @RequestBody ResolveBody body,
            @AuthenticationPrincipal Jwt operator) {
        ResolveAuditUseCase.Resolved resolved = resolve.resolve(operator.getSubject(), auditId,
                Objects.requireNonNull(body.resolution()), Objects.requireNonNull(body.reason()));
        return ResponseEntity.ok()
                .header(MdcKeys.AUDIT_ID_HEADER, resolved.auditId().toString())
                .body(ResolvedView.of(resolved));
    }

    /**
     * @param resolution 그 커맨드가 코어에 적용됐는가
     * @param reason     무엇을 근거로 닫았는가 — 다시 누른 행의 id · 로그 줄 · 현재 상태 질의. 필수, 200자
     */
    public record ResolveBody(@NotNull @Nullable AuditResolution resolution,
            @NotBlank @Size(max = ResolveAuditUseCase.MAX_REASON_LENGTH) @Nullable String reason) {
    }

    /**
     * 적힌 해소 행.
     *
     * @param auditId       해소 행의 id
     * @param targetAuditId 해소한 행
     * @param resolution    판정
     * @param reason        근거
     * @param actor         누가
     * @param createdAt     언제
     */
    public record ResolvedView(UUID auditId, UUID targetAuditId, AuditResolution resolution, String reason,
            String actor, Instant createdAt) {

        static ResolvedView of(ResolveAuditUseCase.Resolved resolved) {
            return new ResolvedView(resolved.auditId(), resolved.targetAuditId(), resolved.resolution(),
                    resolved.reason(), resolved.actor(), resolved.createdAt());
        }
    }
}
