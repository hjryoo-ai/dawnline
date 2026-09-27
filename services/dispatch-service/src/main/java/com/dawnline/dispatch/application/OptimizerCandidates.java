package com.dawnline.dispatch.application;

import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.optimizer.Candidate;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import java.util.ArrayList;
import java.util.List;

/**
 * 저장된 후보 → 최적화기의 입력. 계획({@link RunPlanService})과 함대 판정({@link AssessFleetService})이 <strong>같은 변환</strong>을
 * 써야 함대 판정이 계획이 볼 stop 을 잰다 — 둘이 따로 옮기면 통합(§6.5 1단계)의 입력이 갈라진다.
 */
final class OptimizerCandidates {

    private OptimizerCandidates() {
    }

    static List<Candidate> of(List<DispatchCandidate> candidates) {
        List<Candidate> converted = new ArrayList<>(candidates.size());
        for (DispatchCandidate candidate : candidates) {
            converted.add(new Candidate(OrderId.of(candidate.orderId()),
                    candidate.location(),
                    new Parcel(candidate.weightG(), candidate.volumeCm3(),
                            candidate.requiresCold(), candidate.hazmat()),
                    candidate.promised(), candidate.serviceSeconds(), candidate.priority()));
        }
        return converted;
    }
}
