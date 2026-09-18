package com.dawnline.dispatch.application.port.out;

import com.dawnline.dispatch.domain.CandidateStatus;
import com.dawnline.dispatch.domain.DispatchCandidate;
import java.time.Instant;
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

    /**
     * 계획 결과를 <strong>집합으로</strong> 반영한다 (ADR-029).
     *
     * <p>한 건씩 {@link #findById}→{@link #update} 하지 않는 이유는 성능 이전에 의미다 —
     * 계획은 후보를 하나씩 다루지 않는다. 배정된 것 전부와 미배정된 것 전부, 두 집합이다.
     *
     * <p><strong>전이 규칙은 {@code DispatchCandidate.recordPlanResult} 와 같아야 한다.</strong>
     * 그쪽은 {@code !status.hasProgressedPast(target)} 로 거르고, {@code PLANNED}·
     * {@code UNASSIGNED} 는 진행 축 1 이므로 실제로 통과하는 것은 {@code PENDING}(0) 뿐이다.
     * 구현은 그것을 {@code WHERE … AND status = 'PENDING'} 으로 옮겨 적는다. 같은 규칙을 두 곳이
     * 적으므로 어긋나면 조용하다 — 그래서 둘을 함께 묶는 테스트가 있다
     * ({@code DispatchPersistenceIT}). 특히 취소된 후보를 뒤집지 않아야 한다(ADR-026).
     *
     * @param orderIds 반영할 주문 id 들
     * @param target   {@code PLANNED} 또는 {@code UNASSIGNED}
     * @param at       반영 시각
     * @return 실제로 전이한 행 수
     */
    int recordPlanResult(java.util.Collection<UUID> orderIds, CandidateStatus target, Instant at);
}
