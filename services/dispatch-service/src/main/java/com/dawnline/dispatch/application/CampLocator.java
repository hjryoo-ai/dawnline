package com.dawnline.dispatch.application;

import com.dawnline.dispatch.domain.optimizer.CampDepot;
import java.util.UUID;

/**
 * 캠프 좌표를 준다 (DESIGN.md §6.2 {@code CampDepot}).
 *
 * <p>캠프는 fulfillment 의 참조 데이터다(불변규칙 3 — 다른 서비스 DB 에 붙지 않는다). 그런데
 * 라우트의 출발·복귀 지점이므로 계획에 <strong>반드시</strong> 필요하다.
 *
 * <p>지금 구현은 차량 시드가 들고 있는 캠프에서 좌표를 얻는다. 이벤트로 받아 자기 DB 에 투영하는
 * 것이 §4 의 정석이고, 그 이벤트({@code camp.registered} 같은 것)는 설계서에 아직 없다 —
 * 그것을 만드는 것은 계약 추가라 여기서 조용히 할 일이 아니다.
 */
@FunctionalInterface
public interface CampLocator {

    /**
     * @param campId 캠프 id
     */
    CampDepot locate(UUID campId);
}
