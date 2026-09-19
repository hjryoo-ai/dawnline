package com.dawnline.tracking.application.port.out;

import com.dawnline.tracking.domain.Shipment;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * {@code shipments} 저장소 (DESIGN.md §5.4).
 *
 * <p>넣기와 갱신이 <strong>따로</strong>인 이유: 부르는 쪽이 둘을 이미 구별하고 있다. 개정 소비는
 * 페이로드의 주문들을 한 번에 읽어 오고, 그중 무엇이 새것인지를 그 결과가 말해 준다. 그것을
 * {@code save} 하나로 합치면 어댑터가 같은 질문을 다시 하게 되고, 「없으면 넣는다」가 한 곳이
 * 아니라 두 곳이 된다.
 */
public interface ShipmentRepository {

    /**
     * 주문 id 들로 찾는다.
     *
     * <p><strong>없는 주문은 결과에 없다.</strong> 그 부재는 「아직 배송이 만들어지지 않았다」는
     * 뜻이지 「지워야 한다」는 뜻이 아니다 (ADR-026 — 부재는 값이 아니다).
     *
     * @param orderIds 찾을 주문들. 비어 있으면 빈 목록을 돌려준다
     * @return 찾은 배송들 (순서는 정해지지 않는다)
     */
    List<Shipment> findAll(Collection<UUID> orderIds);

    /**
     * 한 stop 에 묶인 배송들을 찾는다 — 기사 스캔의 대상이다 (§5.4).
     *
     * <p>{@code ix_ship_route (route_id, stop_seq)} 를 타는 유일한 질의다(§5.4 DDL 에 명시된
     * 인덱스, 불변규칙 11). 스캔은 stop 단위로 오고 배송은 주문 단위라 여기서 하나가 여럿이 된다.
     *
     * @param routeId 라우트 id
     * @param stopSeq stop 순번
     * @return 그 stop 의 배송들. 취소된 것도 포함한다 — 「취소된 주문에 스캔이 왔다」를 세려면
     *         그 행이 보여야 한다 (§9.1 {@code dawnline_scan_after_cancel_total})
     */
    List<Shipment> findByRouteAndStop(UUID routeId, int stopSeq);

    /**
     * 한 라우트의 배송을 <strong>순번 이상</strong>으로 찾는다 — 편차 전파와 at-risk 판정의
     * 대상이다 (§5.4, Phase 5-1b).
     *
     * <p>{@code findByRouteAndStop} 과 같은 인덱스({@code ix_ship_route})를 타고 범위만 넓다.
     * {@code fromSeq} 를 받는 이유는 스캔이 만든 편차가 <strong>뒤따르는</strong> stop 의
     * 것이기 때문이다 — 이미 지나온 stop 의 ETA 를 옮기는 일은 아무 물음에도 답하지 않는다.
     * {@code DEPARTED_CAMP} 는 1 을 준다: 캠프 출발은 라우트 전체의 사건이다.
     *
     * @param routeId 라우트 id
     * @param fromSeq 이 순번부터(포함)
     * @return 순번 오름차순의 배송들. 취소·완료된 것도 포함한다 — 무엇을 옮기지 <em>않을지</em>는
     *         애그리거트가 정한다({@code Shipment.projectEta})
     */
    List<Shipment> findByRouteFrom(UUID routeId, int fromSeq);

    /**
     * 새 배송을 넣는다.
     *
     * @param shipment 새 배송
     */
    void insert(Shipment shipment);

    /**
     * 이미 있는 배송을 갱신한다.
     *
     * @param shipment 갱신할 배송
     */
    void update(Shipment shipment);
}
