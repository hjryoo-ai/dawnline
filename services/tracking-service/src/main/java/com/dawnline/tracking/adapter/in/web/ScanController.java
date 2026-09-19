package com.dawnline.tracking.adapter.in.web;

import com.dawnline.tracking.application.port.in.RecordScanUseCase;
import com.dawnline.tracking.application.port.in.RecordScanUseCase.ScanCommand;
import com.dawnline.tracking.application.port.in.RecordScanUseCase.ScanResult;
import com.dawnline.tracking.domain.ScanType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 기사 스캔 API (DESIGN.md §5.4). {@code sim-runner} 의 기사 시뮬레이터가 부른다(§5.6).
 *
 * <h2>재시도해도 안전하다</h2>
 * 멱등 키 헤더가 없다. §8.5 의 멱등 키는 「{@code (routeId, seq, type)} + 상태 머신」이고, 같은
 * 스캔이 다시 오면 이미 지나온 지점이라 {@code STALE} 로 흡수된다 — 별도의 키를 요구하면 단말이
 * 그 키를 재시도 사이에 보존해야 하고, 그것은 오프라인에서 다시 켜지는 기기에 어려운 요구다.
 *
 * <h2>취소 뒤 스캔도 200 이다</h2>
 * 기사가 취소를 받지 못하고 배송한 경우이고, <strong>기사가 고칠 수 있는 문제가 아니다</strong>.
 * 오류로 답하면 단말이 재시도를 반복하고 그동안 다음 stop 이 밀린다. 대신 응답의 해당 주문 줄이
 * {@code AFTER_CANCEL} 이고, 세는 것은 {@code dawnline_scan_after_cancel_total} 이다 (§5.4, §9.1).
 *
 * <h2>버전</h2>
 * 매핑 경로에 {@code v1} 을 박지 않고 {@code {version}} 으로 둔다
 * ([ADR-009](docs/adr/ADR-009-url-path-api-versioning.md)) — 리터럴이면 {@code /api/v2/...} 가
 * 경로 매칭에서 먼저 떨어져 404 가 되고, 사실은 「그 버전을 지원하지 않는다」인데 「그런 것이
 * 없다」로 답하게 된다.
 */
@RestController
@RequestMapping(path = "/api/{version}/routes/{routeId}/stops/{stopSeq}/events", version = "1")
public class ScanController {

    private final RecordScanUseCase recordScan;

    /**
     * @param recordScan 스캔 적용 유스케이스
     */
    public ScanController(RecordScanUseCase recordScan) {
        this.recordScan = Objects.requireNonNull(recordScan, "recordScan");
    }

    /**
     * 스캔 하나를 보고한다.
     *
     * @param routeId 라우트 id
     * @param stopSeq stop 순번 (1부터)
     * @param request 스캔 내용
     * @return stop 에 묶인 주문마다의 결과
     */
    @PostMapping
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "받았다. `orders[]` 의 `outcome` 이 주문마다의 결과다 — "
                            + "`APPLIED`(상태가 옮겨졌다) · `STALE`(이미 지나온 지점, 중복 스캔) · "
                            + "`AFTER_CANCEL`(취소된 주문이라 무시했다). **재시도해도 안전하다**"),
            // 오류 본문의 스키마를 명시한다. 적지 않으면 springdoc 이 <메서드 반환 타입>을
            // 모든 응답에 붙여, 문서가 "404 의 본문은 ScanResult 다" 라고 말한다 — 그 문서를
            // 보고 만든 단말은 오류를 파싱하지 못한다.
            @ApiResponse(responseCode = "400",
                    description = "요청 값이 유효하지 않다. 본문은 Problem Details 이고 `errors[]` 에 "
                            + "어긋난 필드가 모두 들어온다. 지원하지 않는 API 버전도 여기로 온다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404",
                    description = "그 라우트의 그 순번에 배송이 없다. 아직 `route.assigned` 를 받지 "
                            + "못한 창일 수 있으므로 **잠시 후 같은 요청을 다시 보내도 된다**",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409",
                    description = "상태 머신이 허용하지 않는 전이다. 역행 스캔은 409 가 아니라 "
                            + "200 + `STALE` 이므로, 여기까지 오는 것은 상류의 결함이다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public ScanResult report(@PathVariable UUID routeId, @PathVariable int stopSeq,
            @Valid @RequestBody ScanRequest request) {

        return recordScan.record(new ScanCommand(routeId, stopSeq, request.type(),
                request.occurredAt(), request.lat(), request.lng(), request.failureReason()));
    }

    /**
     * 스캔 요청 본문.
     *
     * @param type          {@code DEPARTED_CAMP} · {@code ARRIVED} · {@code COMPLETED} ·
     *                      {@code FAILED} (§5.4)
     * @param occurredAt    사건 시각. <strong>단말이 말한 시각</strong>이지 서버가 받은 시각이
     *                      아니다 — 서버 시각을 쓰면 정시율(§8.1)이 처리 지연만큼 어긋난다.
     *                      기본값을 두지 않는 이유도 같다: 빠뜨린 요청이 조용히 「지금」이 되면
     *                      그 어긋남이 어디서 왔는지 아무도 모른다
     * @param lat           위도. 단말이 위치를 못 잡았으면 비워 둔다
     * @param lng           경도
     * @param failureReason {@code FAILED} 의 사유(부재·주소 오류 등). 다른 종류에 붙이면 400 이다.
     *                      자유 텍스트라 200자로 제한한다 — {@code delivery.status.v1} 의 같은
     *                      필드와 같은 이유(개인정보가 섞이지 않게)이고 같은 길이다
     */
    public record ScanRequest(
            @NotNull ScanType type,
            @NotNull Instant occurredAt,
            @DecimalMin("-90.0") @DecimalMax("90.0") @Nullable Double lat,
            @DecimalMin("-180.0") @DecimalMax("180.0") @Nullable Double lng,
            @Size(max = 200) @Nullable String failureReason) {
    }
}
