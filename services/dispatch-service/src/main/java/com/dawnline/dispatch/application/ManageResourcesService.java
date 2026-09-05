package com.dawnline.dispatch.application;

import com.dawnline.dispatch.application.port.in.ManageResourcesUseCase;
import com.dawnline.dispatch.application.port.in.ResourceViews;
import com.dawnline.dispatch.application.port.out.ReferenceAdmin;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자원·룰 관리 (DESIGN.md §5.3 REST).
 *
 * <h2>왜 위임만 하는 계층이 있는가</h2>
 * 처음에는 컨트롤러에 {@code @Transactional} 을 붙이고 "쓰기가 한 문장이라 유스케이스를 두면
 * 껍데기가 된다" 고 적었다. <strong>ArchUnit 이 그것을 막았고, 규칙이 옳다</strong> — 트랜잭션
 * 경계가 어댑터에 있으면 다음 사람이 그 자리에 판단을 하나 더 얹고, 그때는 옮기기 어렵다.
 * 지금 껍데기인 것은 사실이지만 껍데기는 싸고 경계는 비싸다.
 */
public class ManageResourcesService implements ManageResourcesUseCase {

    private final ReferenceAdmin admin;

    /**
     * @param admin 참조 데이터 관리
     */
    public ManageResourcesService(ReferenceAdmin admin) {
        this.admin = Objects.requireNonNull(admin, "admin");
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResourceViews.RuleView> listRules(@Nullable UUID campId) {
        return admin.listRules(campId);
    }

    @Override
    @Transactional
    public int updateRule(UUID ruleId, ResourceViews.UpdateRule request) {
        return admin.updateRule(ruleId, request.params(), request.enabled());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResourceViews.VehicleView> listVehicles(UUID campId) {
        return admin.listVehicles(campId);
    }

    @Override
    @Transactional
    public UUID createVehicle(ResourceViews.NewVehicle request) {
        return admin.createVehicle(request);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResourceViews.DriverView> listDrivers(UUID campId) {
        return admin.listDrivers(campId);
    }

    @Override
    @Transactional
    public UUID createDriver(ResourceViews.NewDriver request) {
        return admin.createDriver(request);
    }
}
