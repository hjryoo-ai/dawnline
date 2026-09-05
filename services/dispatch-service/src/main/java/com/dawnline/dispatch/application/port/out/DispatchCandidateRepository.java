package com.dawnline.dispatch.application.port.out;

import com.dawnline.dispatch.domain.DispatchCandidate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 계획 후보 저장소 (DESIGN.md §5.3). */
public interface DispatchCandidateRepository {

    /**
     * 없으면 넣는다. 이미 있으면 아무것도 하지 않는다.
     *
     * <p>{@code order_id} 가 PK 라 같은 주문이 두 번 와도 한 행이다 — {@code processed_events}
     * 와 함께 두 겹이다(불변규칙 2). 멱등 기록이 14일 뒤 정리돼도(§4.4) 이쪽은 남는다.
     *
     * @param candidate 후보
     * @return 실제로 넣었으면 참
     */
    boolean insertIfAbsent(DispatchCandidate candidate);

    /**
     * @param orderId 주문 id
     */
    Optional<DispatchCandidate> findById(UUID orderId);

    /**
     * 이 웨이브의 계획 대상 후보들 ({@code status = 'PENDING'}).
     *
     * <p>부분 인덱스가 아니라 {@code ix_cand_wave (wave_id, status)} 를 탄다. 술어를 리터럴로
     * 적는 이유는 CLAUDE.md 코딩 컨벤션과 같다 — 바인드 파라미터로는 플래너가 못 쓴다.
     *
     * @param waveId 웨이브 id
     */
    List<DispatchCandidate> findPlannableInWave(UUID waveId);

    /**
     * @param candidate 갱신할 후보
     */
    void update(DispatchCandidate candidate);
}
