package com.dawnline.fulfillment.adapter.in.web;

import com.dawnline.fulfillment.application.port.in.CloseWaveUseCase;
import com.dawnline.fulfillment.application.port.in.WaveView;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 웨이브 운영 API (DESIGN.md §5.2, ADR-054) — fulfillment 의 첫 REST 표면이다.
 *
 * <h2>운영자용이다</h2>
 * 고객 API 가 아니므로 §7.2 의 레이트 리밋을 붙이지 않는다. 이 경로는 ops-api 를 통해서만 노출된다(불변규칙 4 —
 * 동기 호출은 ops-api → 코어 방향만). 인증도 ops-api 가 맡는다(§5.5).
 *
 * <h2>버전</h2>
 * 매핑 경로에 {@code v1} 을 박아 넣지 않고 {@code {version}} 으로 둔다 (ADR-009 결정 2) — ArchUnit 규칙 8.
 */
@RestController
@RequestMapping(path = "/api/{version}/waves", version = "1")
public class WaveController {

    private final CloseWaveUseCase closeWave;

    /**
     * @param closeWave 조기 마감
     */
    public WaveController(CloseWaveUseCase closeWave) {
        this.closeWave = Objects.requireNonNull(closeWave, "closeWave");
    }

    /**
     * 웨이브를 닫는다 — <strong>컷오프 전에도</strong> (ADR-054).
     *
     * <p>본문은 컷오프 스케줄러와 같은 코드다. 닫힌 뒤 컷오프까지 같은 {@code cutoffAt} 으로 접수되는 주문은
     * 다음 웨이브로 가고 약속이 개정된다({@code dawnline_promise_revised_total{cause="manual"}}).
     *
     * @param waveId  웨이브
     * @param request 이유
     */
    @PostMapping("/{waveId}/close")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "닫았다 — `closeCause=MANUAL`, `wave.closed` 가 outbox 에 들어갔다"),
            // 오류 본문의 스키마를 명시한다. 적지 않으면 springdoc 이 <메서드 반환 타입>을 모든
            // 응답에 붙여 문서가 「404 의 본문은 WaveView」라고 말하게 된다 (§11).
            @ApiResponse(responseCode = "400",
                    description = "`reason` 이 비었거나 200자를 넘는다, 또는 `waveId` 가 UUID 형식이 아니다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "없는 웨이브",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409",
                    description = "`wave-not-open` — 이미 닫혀 있다. 확장 멤버 `currentState`·`closeCause`·`closedAt` 이 "
                            + "누가 언제 닫았는지 말한다. 응답을 못 받은 마감을 다시 눌러 이것을 받았고 `closeCause` 가 "
                            + "`MANUAL` 이면 앞의 요청이 적용된 것이다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public WaveView close(@PathVariable UUID waveId, @Valid @RequestBody CloseWaveRequest request) {
        // @NotBlank 가 통과했으므로 널이 아니다. 유스케이스도 같은 검사를 한 번 더 한다.
        return closeWave.close(waveId, Objects.requireNonNull(request.reason(), "reason"));
    }
}
