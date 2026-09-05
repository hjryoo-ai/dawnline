package com.dawnline.dispatch.adapter.in.web;

import com.dawnline.dispatch.application.port.in.ResourceViews;
import com.dawnline.dispatch.application.port.in.ManageResourcesUseCase;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원·룰 관리 API (DESIGN.md §5.3). 운영자용이다.
 *
 * <p>트랜잭션 경계는 {@link ManageResourcesUseCase} 에 있다 — 어댑터가 아니다(불변규칙 1,
 * ArchUnit 이 강제한다).
 */
@RestController
@RequestMapping("/api/v1")
public class ResourceController {

    private final ManageResourcesUseCase resources;

    /**
     * @param resources 자원·룰 관리 유스케이스
     */
    public ResourceController(ManageResourcesUseCase resources) {
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    /**
     * 룰 목록 (§6.3). 전역과 캠프 오버라이드를 함께 준다.
     *
     * @param campId 캠프. 생략하면 전역만
     */
    @GetMapping("/rules")
    public List<ResourceViews.RuleView> rules(
            @RequestParam(required = false) @Nullable UUID campId) {
        return resources.listRules(campId);
    }

    /**
     * 룰 수정. {@code rule_version} 이 오르고 <strong>다음 계획부터</strong> 적용된다 —
     * 진행 중인 계획은 시작 시점 스냅샷을 쓴다(§6.3).
     *
     * @param ruleId  룰 id
     * @param request 새 파라미터
     */
    @PutMapping("/rules/{ruleId}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "새 rule_version"),
            @ApiResponse(responseCode = "404", description = "없는 룰")})
    public Map<String, Integer> updateRule(@PathVariable UUID ruleId,
            @Valid @RequestBody ResourceViews.UpdateRule request) {
        return Map.of("ruleVersion", resources.updateRule(ruleId, request));
    }

    /**
     * @param campId 캠프
     */
    @GetMapping("/vehicles")
    public List<ResourceViews.VehicleView> vehicles(@RequestParam UUID campId) {
        return resources.listVehicles(campId);
    }

    /**
     * @param request 등록할 차량
     */
    @PostMapping("/vehicles")
    public ResponseEntity<Map<String, UUID>> createVehicle(
            @Valid @RequestBody ResourceViews.NewVehicle request) {
        UUID id = resources.createVehicle(request);
        return ResponseEntity.created(URI.create("/api/v1/vehicles/" + id))
                .body(Map.of("id", id));
    }

    /**
     * @param campId 캠프
     */
    @GetMapping("/drivers")
    public List<ResourceViews.DriverView> drivers(@RequestParam UUID campId) {
        return resources.listDrivers(campId);
    }

    /**
     * @param request 등록할 기사
     */
    @PostMapping("/drivers")
    public ResponseEntity<Map<String, UUID>> createDriver(
            @Valid @RequestBody ResourceViews.NewDriver request) {
        UUID id = resources.createDriver(request);
        return ResponseEntity.created(URI.create("/api/v1/drivers/" + id)).body(Map.of("id", id));
    }
}
