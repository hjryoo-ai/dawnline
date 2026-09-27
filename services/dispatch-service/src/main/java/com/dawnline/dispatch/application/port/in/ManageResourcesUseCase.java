package com.dawnline.dispatch.application.port.in;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** 자원·룰 관리 (DESIGN.md §5.3 REST). */
public interface ManageResourcesUseCase {

    /**
     * @param campId 캠프. {@code null} 이면 전역만
     */
    List<ResourceViews.RuleView> listRules(@Nullable UUID campId);

    /**
     * 룰을 고치고 {@code rule_version} 을 올린다 (§6.3).
     *
     * @param ruleId  룰 id
     * @param request 새 파라미터
     * @return 새 {@code rule_version}
     */
    int updateRule(UUID ruleId, ResourceViews.UpdateRule request);

    /**
     * @param campId 캠프
     */
    List<ResourceViews.VehicleView> listVehicles(UUID campId);

    /**
     * @param request 등록할 차량
     */
    UUID createVehicle(ResourceViews.NewVehicle request);

    /**
     * 차량을 비활성화한다 (ADR-067 결정 5). 끝나지 않은 stop 이 있으면 409 {@code vehicle-in-service}, 없는 차량은 404,
     * 이미 비활성이면 바꾸는 것 없이 그대로 돌려준다.
     *
     * @param vehicleId 차량
     * @return 비활성화한 뒤의 차량
     */
    ResourceViews.VehicleView deactivateVehicle(UUID vehicleId);

    /**
     * @param campId 캠프
     */
    List<ResourceViews.DriverView> listDrivers(UUID campId);

    /**
     * @param request 등록할 기사
     */
    UUID createDriver(ResourceViews.NewDriver request);
}
