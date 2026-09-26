package com.dawnline.ops.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.observability.DawnlineMetrics;
import com.dawnline.ops.application.port.in.OpsCommand;
import com.dawnline.ops.application.port.in.ResolveAuditUseCase;
import com.dawnline.ops.domain.AuditResult;
import com.dawnline.ops.domain.DeliveryOutcome;
import com.dawnline.ops.domain.RouteProgress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ops-api 가 내는 라벨 값은 카탈로그(DawnlineMetrics)의 닫힌 라벨 값 목록과 그 값을 만드는 enum 을 대조한다 (ADR-060 결정 2).
 *
 * <p>닫힌 라벨에 목록 밖의 값이 오면 등록 헬퍼가 실패한다 — 운영에서는 그 유스케이스가 예외를 낸다. enum 에 값이 느는
 * 날 그 실패가 운영의 첫 등록이 아니라 여기서 나야 한다.
 */
class MetricLabelValuesTest {

    @Test
    void 커맨드_action_은_커맨드_전부와_DLQ_재처리와_감사_해소다() {
        List<String> actions = new ArrayList<>(OpsCommand.ACTIONS);
        actions.add(DlqReplayService.ACTION);
        actions.add(ResolveAuditUseCase.ACTION);

        assertThat(DawnlineMetrics.OPS_COMMANDS.label("action").values()).containsExactlyInAnyOrderElementsOf(actions);
    }

    @Test
    void 커맨드_result_는_PENDING_을_뺀_결과_전부다() {
        // PENDING 은 세지 않는다 — 끝나지 않은 커맨드의 수는 audit_logs 가 안다(§9.1).
        assertThat(DawnlineMetrics.OPS_COMMANDS.label("result").values()).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(AuditResult.values()).filter(r -> r != AuditResult.PENDING).map(Enum::name).toList());
    }

    @Test
    void 정시율_basis_와_빠진_결과의_reason() {
        assertThat(DawnlineMetrics.DELIVERY_ON_TIME_RATIO.label(KpiGauges.TAG_BASIS).values())
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(KpiGauges.Basis.values()).map(KpiGauges.Basis::label).toList());
        assertThat(DawnlineMetrics.KPI_EXCLUDED.label(KpiGauges.TAG_REASON).values())
                .containsExactly(KpiGauges.PROMISE_UNKNOWN);
    }

    @Test
    void 결과_수의_outcome_은_배송_결과_전부다() {
        assertThat(DawnlineMetrics.KPI_DELIVERY.label(KpiGauges.TAG_OUTCOME).values())
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(DeliveryOutcome.values()).map(KpiGauges::outcomeLabel).toList());
    }

    @Test
    void 라우트_status_는_진행_전부다() {
        assertThat(DawnlineMetrics.ROUTES.label(KpiGauges.TAG_STATUS).values())
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(RouteProgress.values()).map(RouteProgress::label).toList());
    }
}
