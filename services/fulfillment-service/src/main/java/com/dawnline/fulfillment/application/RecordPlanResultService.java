package com.dawnline.fulfillment.application;

import com.dawnline.fulfillment.application.port.in.RecordPlanResultUseCase;
import com.dawnline.fulfillment.application.port.out.WaveRepository;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.WaveStatus;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계획 결과 기록 (ADR-024).
 *
 * <h2>축 규칙을 여기서 쓴다</h2>
 * {@code plan.completed} 와 {@code plan.failed} 는 <strong>서로 다른 두 토픽</strong>이라 운영자
 * 재실행이 있으면 순서가 뒤바뀔 수 있다(§4.5). {@link WaveStatus#hasProgressedPast} 로 철 지난
 * 이벤트를 걸러 낸다 — {@code PLANNED} 가 축의 끝이자 흡수 상태다.
 *
 * <p>이것은 순서를 봐주는 편법이 아니라 의미가 맞다. 계획된 웨이브를 다시 돌려 실패해도 1회차의
 * 라우트는 여전히 유효하고, 그 웨이브는 계획된 웨이브다.
 *
 * <p><strong>배타 락을 쓰지 않는다.</strong> 이 전이는 편입과 경쟁하지 않는다 — 웨이브가 이미
 * {@code CLOSED} 이후이므로 편입은 애초에 다른 웨이브로 간다. 낙관적 락으로 충분하고, 충돌하면
 * 리스너의 재시도가 받는다.
 */
public class RecordPlanResultService implements RecordPlanResultUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordPlanResultService.class);

    private final WaveRepository waves;

    /**
     * @param waves 웨이브 저장소
     */
    public RecordPlanResultService(WaveRepository waves) {
        this.waves = Objects.requireNonNull(waves, "waves");
    }

    @Override
    @Transactional
    public PlanResultOutcome completed(UUID waveId) {
        return apply(waveId, WaveStatus.PLANNED, Wave::markPlanned);
    }

    @Override
    @Transactional
    public PlanResultOutcome failed(UUID waveId) {
        return apply(waveId, WaveStatus.PLAN_FAILED, Wave::markPlanFailed);
    }

    private PlanResultOutcome apply(UUID waveId, WaveStatus target, Consumer<Wave> transition) {
        Objects.requireNonNull(waveId, "waveId");
        Optional<Wave> found = waves.findById(waveId);
        if (found.isEmpty()) {
            // 아직 마감되지 않았거나(우리가 아직 만들지 않은 웨이브) 이미 정리됐다(ADR-023).
            log.debug("모르는 웨이브의 계획 결과입니다. waveId={}, target={}", waveId, target);
            return PlanResultOutcome.WAVE_NOT_FOUND;
        }
        Wave wave = found.get();
        if (wave.status().hasProgressedPast(target)) {
            // 철 지난 이벤트다 (ADR-024 결정 4). 무시하고 커밋한다 — 리스너가 센다.
            return PlanResultOutcome.STALE;
        }
        transition.accept(wave);
        waves.update(wave);
        return PlanResultOutcome.APPLIED;
    }
}
