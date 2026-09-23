package com.dawnline.dispatch.adapter.in.web;

import com.dawnline.common.error.NotFoundException;
import com.dawnline.dispatch.application.port.in.PlanView;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.PlanQueries;
import com.dawnline.dispatch.domain.PlanMode;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계획 API (DESIGN.md §5.3).
 *
 * <h2>운영자용이다</h2>
 * 고객 API 가 아니므로 §7.2 의 레이트 리밋을 붙이지 않는다. 대신 이 경로는 ops-api 를 통해서만
 * 노출된다(불변규칙 4 — 동기 호출은 ops-api → 코어 방향만).
 *
 * <h2>버전</h2>
 * 매핑 경로에 {@code v1} 을 <strong>박아 넣지 않고</strong> {@code {version}} 으로 둔다
 * (ADR-009 결정 2). 리터럴로 두면 {@code /api/v2/...} 가 경로 매칭에서 먼저 떨어져 <em>404</em>
 * 가 되어, 「그런 리소스가 없다」와 「그 버전은 지원하지 않는다」가 구분되지 않는다.
 * 쓰이지 않는 자리표시자처럼 보여 지우고 싶어지는 코드이고, 지우면 그 구분이 조용히 사라진다 —
 * ArchUnit 규칙 8 이 그것을 막는다.
 */
@RestController
@RequestMapping(path = "/api/{version}/plans", version = "1")
public class PlanController {

    private final RunPlanUseCase runPlan;
    private final PlanQueries queries;

    /**
     * @param runPlan 계획 실행
     * @param queries 조회
     */
    public PlanController(RunPlanUseCase runPlan, PlanQueries queries) {
        this.runPlan = Objects.requireNonNull(runPlan, "runPlan");
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    /**
     * 수동 (재)계획 실행.
     *
     * <p>{@code wave.closed} 소비와 <strong>같은 유스케이스</strong>를 부른다 — 두 경로가 다른
     * 코드를 지나면 "운영자가 돌리면 되는데 자동은 안 된다" 같은 차이가 생긴다.
     *
     * <p>{@code campId} 를 받는 이유: 새 계획을 만들 때 필요한데, 재실행이면 저장된 계획에서
     * 온다. 처음부터 이 웨이브의 계획이 없고 {@code campId} 도 없으면 만들 수 없다.
     *
     * @param waveId   대상 웨이브
     * @param campId   캠프. 이미 계획이 있으면 생략할 수 있다
     * @param strategy 전략 이름. 생략하면 설정의 기본 전략 (§6.6)
     * @param mode     실행 모드. <strong>생략하면 자동 판단</strong>이다 (§6.7, ADR-034) —
     *                 지정하면 사람의 결정이므로 자동 판단을 이기고, 열화로 세지 않는다
     */
    @PostMapping("/{waveId}/run")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "계획 실행 결과"),
            // 오류 본문의 스키마를 명시한다. 적지 않으면 springdoc 이 <메서드 반환 타입>을 모든
            // 응답에 붙여 문서가 「404 의 본문은 RunPlanResponse」라고 말하게 된다 (§11).
            @ApiResponse(responseCode = "400",
                    description = "`mode` 가 §6.7 의 값이 아니거나 id 가 UUID 형식이 아니다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "계획이 없고 campId 도 주지 않았다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<RunPlanResponse> run(@PathVariable UUID waveId,
            @RequestParam(required = false) @Nullable UUID campId,
            @RequestParam(required = false) @Nullable String strategy,
            @RequestParam(required = false) @Nullable String mode) {

        UUID camp = campId != null ? campId : queries.findPlanByWave(waveId)
                .map(PlanView::campId)
                .orElseThrow(() -> NotFoundException.of("RoutePlan", waveId.toString()));

        // 랙은 null 이다 — 웹 경로에는 볼 파티션이 없다. 0 으로 접으면 "랙 없음" 이 되어
        // 열화 조건 하나가 조용히 「아니오」가 된다(ADR-034).
        RunPlanUseCase.Outcome outcome = runPlan.run(new RunPlanCommand(waveId, camp, null,
                strategy, mode == null ? null : PlanMode.valueOf(mode.toUpperCase(Locale.ROOT)),
                null, null));
        return ResponseEntity.ok(new RunPlanResponse(waveId, outcome.name()));
    }

    /**
     * 계획 결과·비용·미배정·설명.
     *
     * @param planId 계획 id
     */
    @GetMapping("/{planId}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "계획 상세"),
            @ApiResponse(responseCode = "400", description = "`planId` 가 UUID 형식이 아니다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "없는 계획",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public PlanView get(@PathVariable UUID planId) {
        return queries.findPlan(planId)
                .orElseThrow(() -> NotFoundException.of("RoutePlan", planId.toString()));
    }

    /**
     * 실행 결과.
     *
     * @param waveId  웨이브 id
     * @param outcome 처리 결과
     */
    public record RunPlanResponse(UUID waveId, String outcome) {
    }
}
