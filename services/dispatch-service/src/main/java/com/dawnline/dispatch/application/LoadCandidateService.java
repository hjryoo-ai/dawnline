package com.dawnline.dispatch.application;

import com.dawnline.dispatch.application.port.in.LoadCandidateUseCase;
import com.dawnline.dispatch.application.port.in.PlannedOrderSnapshot;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.domain.CandidatePriority;
import com.dawnline.dispatch.domain.DispatchCandidate;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code fulfillment.planned} → {@code dispatch_candidates} 적재 (DESIGN.md §5.3).
 *
 * <p>하는 일이 적다. 판정은 fulfillment 가 이미 했고(§5.2), 계획은 아직 시작되지 않았다 —
 * 여기서는 스냅샷을 <strong>있는 그대로</strong> 남긴다.
 *
 * <h2>한 가지만 계산한다 — 우선도</h2>
 * {@link CandidatePriority} 를 스냅샷의 사실에 적용한다([ADR-028]). 이것이 <em>여기</em>인
 * 이유는 셋이다. 어댑터는 계약을 옮기는 곳이지 정책을 아는 곳이 아니고, 도메인은 설정을 읽지
 * 않으며(점수표는 데이터다), 계획 시점에 계산하면 점수표를 바꿀 때 <strong>이미 계획 중인
 * 웨이브의 우선도가 흔들린다</strong> — §6.3 은 계획이 시작 시점 스냅샷으로 돈다고 정했다.
 */
public class LoadCandidateService implements LoadCandidateUseCase {

    private static final Logger log = LoggerFactory.getLogger(LoadCandidateService.class);

    private final DispatchCandidateRepository candidates;
    private final CandidatePriority priority;
    private final Clock clock;

    /**
     * @param candidates 후보 저장소
     * @param priority   우선도 점수표 (설정에서 온다, ADR-028)
     * @param clock      시각 출처 (불변규칙 12)
     */
    public LoadCandidateService(DispatchCandidateRepository candidates,
            CandidatePriority priority, Clock clock) {

        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.priority = Objects.requireNonNull(priority, "priority");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional
    public Outcome load(PlannedOrderSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        DispatchCandidate candidate = DispatchCandidate.load(
                snapshot.orderId(), snapshot.waveId(), snapshot.campId(), snapshot.zoneId(),
                snapshot.location(), snapshot.weightG(), snapshot.volumeCm3(),
                snapshot.requiresCold(), snapshot.hazmat(), snapshot.promised(),
                snapshot.serviceSeconds(), snapshot.promiseRevised(),
                priority.scoreOf(snapshot.promiseRevised(), snapshot.requiresCold()),
                clock.instant());

        if (!candidates.insertIfAbsent(candidate)) {
            // 재전달이다. 스냅샷을 덮어쓰지 않는다 — 첫 번째가 계획의 근거였고, 두 번째가 같은
            // 내용이라는 보장이 없다(at-least-once 는 중복을 막지 다름을 막지 않는다, ADR-020).
            log.debug("이미 적재된 후보입니다: orderId={}", snapshot.orderId());
            return Outcome.DUPLICATE;
        }
        return Outcome.LOADED;
    }
}
