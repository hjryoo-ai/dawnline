package com.dawnline.fulfillment.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dawnline.common.Ids;
import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.fulfillment.application.port.in.CloseWaveUseCase;
import com.dawnline.fulfillment.application.port.in.WaveView;
import com.dawnline.fulfillment.domain.FulfillmentErrorCode;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.WaveCloseCause;
import com.dawnline.fulfillment.domain.WaveStatus;
import java.time.Instant;
import java.util.Map;
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
 * 조기 마감의 HTTP 계층 (DESIGN.md §5.2, ADR-054 결정 6).
 *
 * <p>Docker 없이 도는 슬라이스 테스트다. 여기서 보는 것은 <strong>상태 코드와 오류 모양</strong>이고, 마감이
 * 실제로 무엇을 하는지는 {@code CloseWaveServiceTest} 와 {@code WaveEarlyCloseIT} 가 본다.
 */
@WebMvcTest(controllers = WaveController.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("WaveController — 조기 마감")
class WaveControllerTest {

    private static final Instant CUTOFF = Instant.parse("2026-09-24T01:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CloseWaveUseCase closeWave;

    @Test
    void 닫으면_200_과_WaveView_다() throws Exception {
        UUID waveId = Ids.newId();
        when(closeWave.close(waveId, "물량 조기 소진")).thenReturn(new WaveView(waveId, Ids.newId(),
                ServiceTier.DAWN, CUTOFF, WaveStatus.CLOSED, 42, CUTOFF.minusSeconds(1800), WaveCloseCause.MANUAL));

        mvc.perform(post("/api/v1/waves/{waveId}/close", waveId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"물량 조기 소진\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closeCause").value("MANUAL"))
                .andExpect(jsonPath("$.orderCount").value(42));
    }

    @Test
    void 이유가_비었거나_없거나_200자를_넘으면_400_이고_유스케이스를_부르지_않는다() throws Exception {
        UUID waveId = Ids.newId();
        for (String body : new String[] {"{\"reason\":\"   \"}", "{}", "{\"reason\":\"" + "가".repeat(201) + "\"}"}) {
            mvc.perform(post("/api/v1/waves/{waveId}/close", waveId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation-failed"));
        }
        verify(closeWave, never()).close(any(), anyString());
    }

    @Test
    void 없는_웨이브는_404_이고_Problem_Details_다() throws Exception {
        UUID waveId = Ids.newId();
        when(closeWave.close(eq(waveId), anyString())).thenThrow(NotFoundException.of("Wave", waveId));

        mvc.perform(post("/api/v1/waves/{waveId}/close", waveId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"r\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void 이미_닫혀_있으면_409_wave_not_open_이고_누가_닫았는지_최상위에_싣는다() throws Exception {
        // 이 본문이 감사 UNKNOWN 을 닫는 근거다 — 확장 멤버가 중첩되면 ops-api 가 읽지 못한다(ADR-052).
        UUID waveId = Ids.newId();
        when(closeWave.close(eq(waveId), anyString())).thenThrow(new DomainException(
                FulfillmentErrorCode.WAVE_NOT_OPEN, "이미 CLOSED",
                Map.of("currentState", "CLOSED", "closeCause", "MANUAL", "closedAt", CUTOFF.toString())));

        mvc.perform(post("/api/v1/waves/{waveId}/close", waveId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"r\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("wave-not-open"))
                .andExpect(jsonPath("$.closeCause").value("MANUAL"))
                .andExpect(jsonPath("$.currentState").value("CLOSED"));
    }

    @Test
    void 지원하지_않는_버전은_404_가_아니라_400_이다() throws Exception {
        // ADR-009 결정 2 — 경로 매칭이 아니라 버전 해석이 거절한다.
        mvc.perform(post("/api/v2/waves/{waveId}/close", Ids.newId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"r\"}"))
                .andExpect(status().isBadRequest());
    }
}
