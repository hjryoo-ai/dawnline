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
import java.util.List;
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
 * <h2>열쇠는 본문의 {@code orderIds} 다 — 경로는 확인용이다</h2>
 * 경로의 {@code routeId}·{@code stopSeq} 는 <strong>확인용 컨텍스트</strong>이지 조회 조건이
 * 아니다 ([ADR-047](docs/adr/ADR-047-delivery-status-is-a-fact-not-a-revision.md) 결정 1).
 * 기사는 개정 r 의 번호로 찍는데 tracking 은 이미 r+1 을 적용했을 수 있고, 그때 번호로 찾으면
 * <em>엉뚱한 주문을 완료로 적거나</em> 404 다. 경로를 그대로 둔 이유는 그 둘이 사라지는 것이
 * 아니라 역할이 바뀌기 때문이다 — 어긋남은 {@code dawnline_scan_after_relocate_total} 이 센다.
 *
 * <h2>재시도해도 안전하다</h2>
 * 멱등 키 헤더가 없다. §8.5 의 멱등 키는 「{@code (orderIds, type)} + 상태 머신」이고, 같은
 * 스캔이 다시 오면 이미 지나온 지점이라 {@code STALE} 로 흡수된다 — 별도의 키를 요구하면 단말이
 * 그 키를 재시도 사이에 보존해야 하고, 그것은 오프라인에서 다시 켜지는 기기에 어려운 요구다.
 * 키에 번호가 없는 것도 같은 이유다: 재시도 사이에 개정이 오면 번호는 뜻이 변한다.
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
     * <p><strong>{@code DEPARTED_CAMP} 만 범위가 다르다</strong> — 캠프 출발은 라우트의 사건이라
     * 그 라우트의 배송 전부에 적용된다(§5.4). 그래서 그 종류만 {@code orderIds} 를 싣지 않으며,
     * 실으면 400 이다. 응답의 {@code orders[]} 에는 라우트의 모든 주문이 들어온다.
     *
     * @param routeId 라우트 id. <strong>확인용</strong>이다
     * @param stopSeq stop 순번 (1부터). 마찬가지로 확인용이고, {@code DEPARTED_CAMP} 에서는
     *                읽히지도 않는다
     * @param request 스캔 내용
     * @return 주문마다의 결과 ({@code DEPARTED_CAMP} 는 라우트 전체)
     */
    @PostMapping
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "받았다. `orders[]` 의 `outcome` 이 주문마다의 결과다 — "
                            + "`APPLIED`(상태가 옮겨졌다) · `STALE`(이미 지나온 지점, 중복 스캔) · "
                            + "`AFTER_CANCEL`(취소된 주문이라 무시했다). **재시도해도 안전하다**. "
                            + "응답의 `routeId`·`stopSeq` 는 **요청을 그대로 되비친 값**이다 — "
                            + "개정이 그 주문을 옮겼으면 실제로 적용된 자리는 다르고, 단말이 "
                            + "알아야 하는 것은 주문마다의 `outcome` 이다. "
                            + "`DEPARTED_CAMP` 는 `orderIds` 없이 **라우트 전체**에 "
                            + "적용되므로 `orders[]` 에 그 라우트의 모든 주문이 들어온다 — "
                            + "기사는 캠프를 한 번 떠나고, 그 순간 모든 배송이 길 위에 있다"),
            // 오류 본문의 스키마를 명시한다. 적지 않으면 springdoc 이 <메서드 반환 타입>을
            // 모든 응답에 붙여, 문서가 "404 의 본문은 ScanResult 다" 라고 말한다 — 그 문서를
            // 보고 만든 단말은 오류를 파싱하지 못한다.
            @ApiResponse(responseCode = "400",
                    description = "요청 값이 유효하지 않다. 본문은 Problem Details 이고 `errors[]` 에 "
                            + "어긋난 필드가 모두 들어온다. **`orderIds` 가 비어 있는 stop 스캔**과 "
                            + "**`orderIds` 를 실은 `DEPARTED_CAMP`** 도 여기로 온다 — 캠프 출발은 "
                            + "라우트의 사건이라 열쇠가 라우트다. 지원하지 않는 API 버전도 마찬가지다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404",
                    description = "그 `orderIds` 의 배송이 하나도 없다(`DEPARTED_CAMP` 는 그 "
                            + "라우트에 배송이 하나도 없다). 아직 `route.assigned` 를 받지 "
                            + "못한 창일 수 있으므로 **잠시 후 같은 요청을 다시 보내도 된다**",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409",
                    description = "상태 머신이 허용하지 않는 전이다. 역행 스캔은 409 가 아니라 "
                            + "200 + `STALE` 이므로, 여기까지 오는 것은 상류의 결함이다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public ScanResult report(@PathVariable UUID routeId, @PathVariable int stopSeq,
            @Valid @RequestBody ScanRequest request) {

        List<UUID> orderIds = request.orderIds() == null ? List.of() : request.orderIds();
        return recordScan.record(new ScanCommand(routeId, stopSeq, orderIds, request.type(),
                request.occurredAt(), request.lat(), request.lng(), request.failureReason()));
    }

    /**
     * 스캔 요청 본문.
     *
     * @param orderIds      찍은 송장들 — <strong>이 요청의 열쇠</strong>다 (ADR-047 결정 1).
     *                      {@code DEPARTED_CAMP} 에서만 비운다: 캠프 출발은 라우트의 사건이라
     *                      열쇠가 라우트이고, 그 종류에 실으면 400 이다. 길이를 200 으로 제한하는
     *                      이유는 stop 하나의 주문 수를 계획이 정하는 것과 달리 <em>요청의 길이는
     *                      클라이언트가 정하기</em> 때문이다 — 인증이 없는 API(§10)에서 상한 없는
     *                      배열은 IN 목록의 길이를 그대로 단말에 넘긴다
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
            @Size(max = 200) @Nullable List<@NotNull UUID> orderIds,
            @NotNull ScanType type,
            @NotNull Instant occurredAt,
            @DecimalMin("-90.0") @DecimalMax("90.0") @Nullable Double lat,
            @DecimalMin("-180.0") @DecimalMax("180.0") @Nullable Double lng,
            @Size(max = 200) @Nullable String failureReason) {
    }
}
