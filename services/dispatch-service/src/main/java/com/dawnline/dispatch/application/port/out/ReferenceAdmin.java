package com.dawnline.dispatch.application.port.out;

import com.dawnline.dispatch.application.port.in.ResourceViews;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 참조 데이터 관리 (DESIGN.md §5.3 REST — rules · vehicles · drivers).
 *
 * <p>조회 포트({@link PlanQueries})와 나눠 둔 이유는 <strong>바꾸는 일</strong>이기 때문이다.
 * 룰 수정은 {@code rule_version} 을 올리고, 그 값이 다음 계획부터 적용된다(§6.3).
 */
public interface ReferenceAdmin {

    /**
     * 룰 목록. 전역과 캠프 오버라이드를 모두 돌려준다 — 운영자가 무엇이 무엇을 덮는지 봐야 한다.
     *
     * @param campId 캠프. {@code null} 이면 전역만
     */
    List<ResourceViews.RuleView> listRules(@Nullable UUID campId);

    /**
     * 룰을 고치고 {@code rule_version} 을 올린다.
     *
     * <p>진행 중인 계획은 시작 시점 스냅샷을 쓰므로 영향을 받지 않는다(§6.3).
     *
     * @param ruleId  룰 id
     * @param params  새 파라미터
     * @param enabled 켤지 끌지
     * @return 새 {@code rule_version}
     */
    int updateRule(UUID ruleId, Map<String, Object> params, boolean enabled);

    /**
     * @param campId 캠프
     */
    List<ResourceViews.VehicleView> listVehicles(UUID campId);

    /**
     * @param request 등록할 차량
     */
    UUID createVehicle(ResourceViews.NewVehicle request);

    /**
     * @param campId 캠프
     */
    List<ResourceViews.DriverView> listDrivers(UUID campId);

    /**
     * @param request 등록할 기사
     */
    UUID createDriver(ResourceViews.NewDriver request);
}
