package com.dawnline.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.fulfillment.application.port.in.RecordPlanResultUseCase.PlanResultOutcome;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.WaveStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 계획 결과 기록 (ADR-024). 특히 <strong>재실행이 만드는 순서 뒤바뀜</strong>. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("RecordPlanResultService — PLANNED 는 흡수 상태다")
class RecordPlanResultServiceTest {

    private static final Instant CUTOFF = Instant.parse("2026-09-06T01:00:00Z");

    private final InMemoryFulfillmentRepositories repositories = new InMemoryFulfillmentRepositories();
    private final RecordPlanResultService service =
            new RecordPlanResultService(repositories.waveRepository());

    private Wave closedWave() {
        Wave wave = Wave.open(Ids.newId(), Ids.newId(), ServiceTier.SAME_DAY, CUTOFF);
        wave.beginClosing();
        wave.close(CUTOFF.plusSeconds(120), 3);
        repositories.waveRepository().insertIfAbsent(wave);
        repositories.waveRepository().update(wave);
        return wave;
    }

    private WaveStatus statusOf(UUID waveId) {
        return repositories.waveRepository().findById(waveId).orElseThrow().status();
    }

    @Test
    void 계획_완료로_PLANNED_가_된다() {
        // 이 전이가 발화해야 ADR-023 의 정리 배치가 PLANNED 주문 행을 지울 수 있다.
        Wave wave = closedWave();

        assertThat(service.completed(wave.id())).isEqualTo(PlanResultOutcome.APPLIED);
        assertThat(statusOf(wave.id())).isEqualTo(WaveStatus.PLANNED);
    }

    @Test
    void 계획_실패로_PLAN_FAILED_가_된다() {
        Wave wave = closedWave();

        assertThat(service.failed(wave.id())).isEqualTo(PlanResultOutcome.APPLIED);
        assertThat(statusOf(wave.id())).isEqualTo(WaveStatus.PLAN_FAILED);
    }

    @Test
    void 운영자_재실행이_성공하면_되살아난다() {
        // §5.3 이 "운영자 재실행 가능" 이라고 적어 둔 경로가 돌아올 자리다 (ADR-024 결정 3).
        Wave wave = closedWave();
        service.failed(wave.id());

        assertThat(service.completed(wave.id())).isEqualTo(PlanResultOutcome.APPLIED);
        assertThat(statusOf(wave.id())).isEqualTo(WaveStatus.PLANNED);
    }

    @Test
    void 계획된_웨이브에_늦게_온_실패는_무시한다() {
        // 두 이벤트는 다른 토픽이라 재실행 시 순서가 뒤바뀔 수 있다. 그대로 두면 라우트가
        // 이미 나간 웨이브가 실패로 표시된다 (ADR-024 결정 4).
        Wave wave = closedWave();
        service.completed(wave.id());

        assertThat(service.failed(wave.id())).isEqualTo(PlanResultOutcome.STALE);
        assertThat(statusOf(wave.id())).isEqualTo(WaveStatus.PLANNED);
    }

    @Test
    void 같은_완료가_두_번_와도_상태는_그대로다() {
        // 중복은 processed_events 가 앞에서 거르지만, 운영자가 재실행하면 다른 eventId 로 또 온다.
        Wave wave = closedWave();
        service.completed(wave.id());

        assertThat(service.completed(wave.id())).isEqualTo(PlanResultOutcome.STALE);
        assertThat(statusOf(wave.id())).isEqualTo(WaveStatus.PLANNED);
    }

    @Test
    void 모르는_웨이브는_조용히_지나간다() {
        // 아직 마감되지 않았거나 이미 정리됐다(ADR-023). 예외로 만들면 DLQ 로 간다.
        assertThat(service.completed(Ids.newId())).isEqualTo(PlanResultOutcome.WAVE_NOT_FOUND);
    }
}
