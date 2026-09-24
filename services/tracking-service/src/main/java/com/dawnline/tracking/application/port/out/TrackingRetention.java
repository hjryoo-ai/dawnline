package com.dawnline.tracking.application.port.out;

import java.time.Instant;

/**
 * {@code shipments} · {@code route_revisions} 보존 정리의 삭제 (ADR-058, DESIGN.md §5.4 「보존」).
 *
 * <p>세 삭제 모두 <strong>오래된 행부터 {@code limit} 개</strong>를 지우고 지운 수를 돌려준다. {@code limit} 을
 * 못 채운 반환이 「대상이 소진됐다」의 유일한 신호이므로, 각 삭제는 매번 앞으로 나아가야 한다.
 */
public interface TrackingRetention {

    /**
     * 종결 배송({@code COMPLETED}·{@code FAILED}·{@code CANCELLED}) 중 {@code updated_at} 이 임계 전인 것.
     *
     * @param updatedBefore 이 시각 전에 마지막으로 바뀐 행
     * @param limit         최대 행 수 (1 이상)
     * @return 지운 행 수
     */
    int deleteSettledShipmentsUpdatedBefore(Instant updatedBefore, int limit);

    /**
     * 상태와 무관하게 {@code updated_at} 이 임계 전인 배송 — 상한이다. 정리이지 정책이 아니다(ADR-058 결정 3).
     *
     * @param updatedBefore 이 시각 전에 마지막으로 바뀐 행
     * @param limit         최대 행 수 (1 이상)
     * @return 지운 행 수
     */
    int deleteShipmentsUpdatedBefore(Instant updatedBefore, int limit);

    /**
     * {@code applied_at} 이 임계 전이고 <strong>참조하는 배송이 없는</strong> 라우트 개정.
     *
     * @param appliedBefore 이 시각 전에 마지막으로 개정을 적용한 라우트
     * @param limit         최대 행 수 (1 이상)
     * @return 지운 행 수
     */
    int deleteUnreferencedRevisionsAppliedBefore(Instant appliedBefore, int limit);
}
