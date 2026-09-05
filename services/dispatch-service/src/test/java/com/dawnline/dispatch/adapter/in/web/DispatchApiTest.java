package com.dawnline.dispatch.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dawnline.common.Ids;
import com.dawnline.common.error.ConflictException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.dispatch.application.port.in.ManageResourcesUseCase;
import com.dawnline.dispatch.application.port.in.PlanView;
import com.dawnline.dispatch.application.port.in.ReassignStopUseCase;
import com.dawnline.dispatch.application.port.in.ResourceViews;
import com.dawnline.dispatch.application.port.in.RouteView;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.PlanQueries;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 운영자 API 의 HTTP 계층 (DESIGN.md §5.3).
 *
 * <p>Docker 없이 도는 슬라이스 테스트다. 여기서 보는 것은 <strong>상태 코드와 오류 모양</strong>
 * 이고, SQL 이 맞는지는 IT 가 본다 — 두 층을 한 테스트로 보면 실패했을 때 어느 쪽인지 모른다.
 */
@WebMvcTest(controllers = {PlanController.class, RouteController.class, ResourceController.class})
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("DispatchApi — 운영자 API")
class DispatchApiTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RunPlanUseCase runPlan;

    @MockitoBean
    private PlanQueries queries;

    @MockitoBean
    private ReassignStopUseCase reassign;

    @MockitoBean
    private ManageResourcesUseCase resources;

    private static PlanView planView(UUID planId, UUID waveId, UUID campId) {
        return new PlanView(planId, waveId, campId, "PUBLISHED", "sweep-greedy-nn", "FULL", 1,
                Instant.parse("2026-09-06T01:00:00Z"), Instant.parse("2026-09-06T01:00:01Z"),
                1_500_000L, 480, 20, 674, null,
                List.of(new PlanView.RouteSummary(Ids.newId(), Ids.newId(), 1, 1, 12, 8_420, 2_340,
                        21_500)),
                List.of(new PlanView.ExplanationView(Ids.newId(), "UNASSIGNED", "cold-chain", null,
                        "{\"reason\":\"no cold vehicle\"}")));
    }

    @Test
    void 계획_상세는_비용과_미배정과_설명을_준다() throws Exception {
        UUID planId = Ids.newId();
        when(queries.findPlan(planId))
                .thenReturn(Optional.of(planView(planId, Ids.newId(), Ids.newId())));

        mvc.perform(get("/api/v1/plans/{planId}", planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCostKrw").value(1_500_000))
                .andExpect(jsonPath("$.unassignedCount").value(20))
                // §6.3 — 설명이 없으면 룰을 데이터로 둔 이유가 사라진다.
                .andExpect(jsonPath("$.explanations[0].ruleName").value("cold-chain"));
    }

    @Test
    void 없는_계획은_404_이고_Problem_Details_다() throws Exception {
        UUID planId = Ids.newId();
        when(queries.findPlan(planId)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/plans/{planId}", planId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                // type 이 비면 클라이언트가 사람이 읽는 문장으로 분기해야 한다.
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    void 재실행은_같은_유스케이스를_부른다() throws Exception {
        UUID waveId = Ids.newId();
        UUID campId = Ids.newId();
        when(runPlan.run(any())).thenReturn(RunPlanUseCase.Outcome.PUBLISHED);

        mvc.perform(post("/api/v1/plans/{waveId}/run", waveId).param("campId", campId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PUBLISHED"));
    }

    @Test
    void 계획도_campId_도_없으면_404_다() throws Exception {
        UUID waveId = Ids.newId();
        when(queries.findPlanByWave(waveId)).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/plans/{waveId}/run", waveId))
                .andExpect(status().isNotFound());
    }

    @Test
    void 라우트_상세는_stop_을_순서대로_준다() throws Exception {
        UUID routeId = Ids.newId();
        when(queries.findRoute(routeId)).thenReturn(Optional.of(new RouteView(routeId, Ids.newId(),
                Ids.newId(), Ids.newId(), "PLANNED", 1, 8_420, 2_340, 21_500,
                List.of(new RouteView.StopView(Ids.newId(), 1, 37.4979, 127.0276,
                        Instant.parse("2026-09-06T02:00:00Z"),
                        Instant.parse("2026-09-06T02:01:30Z"), 90, "PLANNED",
                        List.of(Ids.newId()))))));

        mvc.perform(get("/api/v1/routes/{routeId}", routeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.stops[0].seq").value(1))
                .andExpect(jsonPath("$.stops[0].status").value("PLANNED"));
    }

    @Test
    void 재배정은_두_개정_번호를_돌려준다() throws Exception {
        UUID from = Ids.newId();
        UUID to = Ids.newId();
        UUID orderId = Ids.newId();
        when(reassign.reassign(eq(from), eq(orderId), eq(to)))
                .thenReturn(new ReassignStopUseCase.Result(orderId, from, 2, to, 3));

        mvc.perform(post("/api/v1/routes/{routeId}/stops/{orderId}/reassign", from, orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetRouteId\":\"" + to + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromRevision").value(2))
                .andExpect(jsonPath("$.toRevision").value(3));
    }

    @Test
    void 옮기면_룰을_어기는_경우_409_다() throws Exception {
        UUID from = Ids.newId();
        UUID to = Ids.newId();
        UUID orderId = Ids.newId();
        when(reassign.reassign(any(), any(), any()))
                .thenThrow(new ConflictException("옮기면 하드 룰을 어깁니다: 용량 초과", Map.of()));

        mvc.perform(post("/api/v1/routes/{routeId}/stops/{orderId}/reassign", from, orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetRouteId\":\"" + to + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void 목적지가_없으면_400_이고_필드를_알려_준다() throws Exception {
        mvc.perform(post("/api/v1/routes/{routeId}/stops/{orderId}/reassign", Ids.newId(),
                        Ids.newId())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("targetRouteId"));
    }

    @Test
    void 룰_수정은_새_버전을_돌려준다() throws Exception {
        UUID ruleId = Ids.newId();
        when(resources.updateRule(eq(ruleId), any())).thenReturn(8);

        mvc.perform(put("/api/v1/rules/{ruleId}", ruleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"params\":{\"max\":100},\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleVersion").value(8));
    }

    @Test
    void 룰_목록은_전역과_캠프를_함께_준다() throws Exception {
        UUID campId = Ids.newId();
        when(resources.listRules(campId)).thenReturn(List.of(
                new ResourceViews.RuleView(Ids.newId(), null, "cold-chain",
                        "VEHICLE_ATTRIBUTE_MATCH", "HARD", 10, true, 1, "{}"),
                new ResourceViews.RuleView(Ids.newId(), campId, "cold-chain",
                        "VEHICLE_ATTRIBUTE_MATCH", "HARD", 10, true, 2, "{}")));

        mvc.perform(get("/api/v1/rules").param("campId", campId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].campId").doesNotExist());
    }

    @Test
    void 차량_등록은_201_과_Location_을_준다() throws Exception {
        UUID id = Ids.newId();
        when(resources.createVehicle(any())).thenReturn(id);

        mvc.perform(post("/api/v1/vehicles").contentType(MediaType.APPLICATION_JSON).content("""
                        {"campId":"%s","code":"V-9001","type":"VAN","maxWeightG":400000,
                         "maxVolumeCm3":1200000,"cold":true,"allowsHazmat":false,
                         "fixedCostKrw":45000,"costPerKmKrw":600,"costPerMinKrw":250,
                         "shiftStart":"06:00:00","shiftEnd":"22:00:00"}
                        """.formatted(Ids.newId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void 용량이_0_이면_400_이다() throws Exception {
        mvc.perform(post("/api/v1/vehicles").contentType(MediaType.APPLICATION_JSON).content("""
                        {"campId":"%s","code":"V-9002","type":"VAN","maxWeightG":0,
                         "maxVolumeCm3":1200000,"cold":true,"allowsHazmat":false,
                         "fixedCostKrw":45000,"costPerKmKrw":600,"costPerMinKrw":250,
                         "shiftStart":"06:00:00","shiftEnd":"22:00:00"}
                        """.formatted(Ids.newId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 기사_목록은_캠프로_좁힌다() throws Exception {
        UUID campId = Ids.newId();
        when(resources.listDrivers(campId)).thenReturn(List.of(new ResourceViews.DriverView(
                Ids.newId(), campId, Ids.newId(), "D-0001", "기사 0001", "AVAILABLE")));

        mvc.perform(get("/api/v1/drivers").param("campId", campId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"));
    }

    @Test
    void 차량_목록은_근무창을_준다() throws Exception {
        UUID campId = Ids.newId();
        when(resources.listVehicles(campId)).thenReturn(List.of(new ResourceViews.VehicleView(
                Ids.newId(), campId, "V-0001", "VAN", 400_000, 1_200_000, true, false, 45_000,
                600, 250, LocalTime.of(6, 0), LocalTime.of(22, 0), true)));

        mvc.perform(get("/api/v1/vehicles").param("campId", campId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].shiftStart").value("06:00:00"));
    }
}
