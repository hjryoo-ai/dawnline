package com.dawnline.dispatch.application.port.out;

import com.dawnline.dispatch.application.port.in.ResourceViews;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * @param request 등록할 차량 — {@code source} 는 {@link ResourceViews.NewVehicle#effectiveSource()}
     */
    UUID createVehicle(ResourceViews.NewVehicle request);

    /**
     * 차량 행을 잠그고 읽는다({@code FOR UPDATE}) — 비활성화의 판정과 쓰기 사이에 다른 비활성화가 끼지 않게.
     *
     * @param vehicleId 차량
     * @return 없으면 비어 있다
     */
    Optional<ResourceViews.VehicleView> lockVehicle(UUID vehicleId);

    /**
     * 그 차량의 라우트에서 <strong>끝나지 않은</strong> stop 수 — 보존과 같은 정의(ADR-067 결정 5).
     *
     * @param vehicleId 차량
     */
    long unfinishedStops(UUID vehicleId);

    /**
     * 비활성으로 바꾼다. 다음 계획부터 빠진다 — 과거 라우트는 그대로다.
     *
     * @param vehicleId 차량
     */
    void deactivate(UUID vehicleId);

    /**
     * @param campId 캠프
     */
    List<ResourceViews.DriverView> listDrivers(UUID campId);

    /**
     * @param request 등록할 기사
     */
    UUID createDriver(ResourceViews.NewDriver request);
}
