package com.dawnline.dispatch.adapter.in.web;

import com.dawnline.dispatch.application.port.in.ResourceViews;
import com.dawnline.dispatch.application.port.in.ManageResourcesUseCase;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ProblemDetail;
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
 *
 * <h2>버전</h2>
 * 매핑 경로에 {@code v1} 을 <strong>박아 넣지 않고</strong> {@code {version}} 으로 둔다
 * (ADR-009 결정 2). 리터럴로 두면 {@code /api/v2/...} 가 경로 매칭에서 먼저 떨어져 <em>404</em>
 * 가 되어, 「그런 리소스가 없다」와 「그 버전은 지원하지 않는다」가 구분되지 않는다.
 * 쓰이지 않는 자리표시자처럼 보여 지우고 싶어지는 코드이고, 지우면 그 구분이 조용히 사라진다 —
 * ArchUnit 규칙 8 이 그것을 막는다.
 */
@RestController
@RequestMapping(path = "/api/{version}", version = "1")
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
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "룰 목록. 전역과 캠프 오버라이드가 함께 온다"),
            @ApiResponse(responseCode = "400", description = "`campId` 가 UUID 형식이 아니다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
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
            @ApiResponse(responseCode = "400", description = "`params` 가 없거나 id 가 UUID 형식이 아니다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "없는 룰",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public RuleVersion updateRule(@PathVariable UUID ruleId,
            @Valid @RequestBody ResourceViews.UpdateRule request) {
        return new RuleVersion(resources.updateRule(ruleId, request));
    }

    /**
     * @param campId 캠프
     */
    @GetMapping("/vehicles")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "그 캠프의 차량"),
            @ApiResponse(responseCode = "400", description = "`campId` 가 없거나 UUID 형식이 아니다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public List<ResourceViews.VehicleView> vehicles(@RequestParam UUID campId) {
        return resources.listVehicles(campId);
    }

    /**
     * @param request 등록할 차량
     */
    @PostMapping("/vehicles")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록됨. `Location` 에 차량 주소가 온다"),
            @ApiResponse(responseCode = "400", description = "필수 값이 없거나 형식이 올바르지 않다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<CreatedId> createVehicle(
            @Valid @RequestBody ResourceViews.NewVehicle request) {
        UUID id = resources.createVehicle(request);
        return ResponseEntity.created(URI.create("/api/v1/vehicles/" + id))
                .body(new CreatedId(id));
    }

    /**
     * @param campId 캠프
     */
    @GetMapping("/drivers")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "그 캠프의 기사"),
            @ApiResponse(responseCode = "400", description = "`campId` 가 없거나 UUID 형식이 아니다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public List<ResourceViews.DriverView> drivers(@RequestParam UUID campId) {
        return resources.listDrivers(campId);
    }

    /**
     * @param request 등록할 기사
     */
    @PostMapping("/drivers")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록됨. `Location` 에 기사 주소가 온다"),
            @ApiResponse(responseCode = "400", description = "필수 값이 없거나 형식이 올바르지 않다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<CreatedId> createDriver(
            @Valid @RequestBody ResourceViews.NewDriver request) {
        UUID id = resources.createDriver(request);
        return ResponseEntity.created(URI.create("/api/v1/drivers/" + id)).body(new CreatedId(id));
    }

    /**
     * 새 rule_version.
     *
     * <p>{@code Map.of("ruleVersion", …)} 이 아니라 <strong>이름 있는 타입</strong>인 이유:
     * springdoc 은 맵을 {@code type: object} 로 적고, 그것은 「본문이 있다」와 「그 타입은 말하지
     * 않는다」를 동시에 말한다 — 문서를 보고 만든 클라이언트는 역직렬화할 타입을 만들 수 없다
     * (§11, order-service 의 {@code ResponseEntity<Object>} 와 같은 부류). 직렬화 결과는 같다.
     *
     * @param ruleVersion 오른 뒤의 버전
     */
    public record RuleVersion(int ruleVersion) {
    }

    /**
     * 만들어진 자원의 id. 차량과 기사가 같은 모양이라 한 타입이다.
     *
     * @param id 새 자원 id
     */
    public record CreatedId(UUID id) {
    }
}
