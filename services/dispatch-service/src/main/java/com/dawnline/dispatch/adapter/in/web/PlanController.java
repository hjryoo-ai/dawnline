package com.dawnline.dispatch.adapter.in.web;

import com.dawnline.common.error.NotFoundException;
import com.dawnline.dispatch.application.port.in.PlanView;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.PlanQueries;
import com.dawnline.dispatch.domain.PlanMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
 */
@RestController
@RequestMapping("/api/v1/plans")
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
     * @param mode     실행 모드. 생략하면 {@code FULL} (§6.7)
     */
    @PostMapping("/{waveId}/run")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "계획 실행 결과"),
            @ApiResponse(responseCode = "404", description = "계획이 없고 campId 도 주지 않았다")})
    public ResponseEntity<RunPlanResponse> run(@PathVariable UUID waveId,
            @RequestParam(required = false) @Nullable UUID campId,
            @RequestParam(required = false) @Nullable String strategy,
            @RequestParam(required = false) @Nullable String mode) {

        UUID camp = campId != null ? campId : queries.findPlanByWave(waveId)
                .map(PlanView::campId)
                .orElseThrow(() -> NotFoundException.of("RoutePlan", waveId.toString()));

        RunPlanUseCase.Outcome outcome = runPlan.run(new RunPlanCommand(waveId, camp, null,
                strategy, mode == null ? null : PlanMode.valueOf(mode.toUpperCase(Locale.ROOT)),
                null));
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
            @ApiResponse(responseCode = "404", description = "없는 계획")})
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
