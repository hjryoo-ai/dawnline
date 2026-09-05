package com.dawnline.dispatch.application.port.in;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** 자원 관리 API 의 표현 (DESIGN.md §5.3). */
public final class ResourceViews {

    private ResourceViews() {
    }

    /**
     * 룰 한 줄 (§6.3).
     *
     * @param id          룰 id
     * @param campId      캠프. {@code null} 이면 전역
     * @param name        이름. {@code Explanation.ruleName} 으로 나간다
     * @param type        타입
     * @param severity    심각도
     * @param priority    평가 순서
     * @param enabled     켜져 있는가
     * @param ruleVersion 버전. 다음 계획부터 적용된다
     * @param params      파라미터 (JSON 문자열)
     */
    public record RuleView(UUID id, @Nullable UUID campId, String name, String type,
            String severity, int priority, boolean enabled, int ruleVersion, String params) {
    }

    /**
     * 룰 수정 요청.
     *
     * @param params  새 파라미터
     * @param enabled 켤지 끌지
     */
    public record UpdateRule(@NotNull Map<String, Object> params, boolean enabled) {
    }

    /**
     * 차량 한 대.
     *
     * @param id             차량 id
     * @param campId         캠프
     * @param code           운영자가 부르는 이름
     * @param type           차종
     * @param maxWeightG     최대 중량(g)
     * @param maxVolumeCm3   최대 부피(㎤)
     * @param cold           냉장 차량인가
     * @param allowsHazmat   위험물 허용인가
     * @param fixedCostKrw   고정비
     * @param costPerKmKrw   km 당 비용
     * @param costPerMinKrw  분당 비용
     * @param shiftStart     근무 시작 (벽시계)
     * @param shiftEnd       근무 종료
     * @param active         가용한가
     */
    public record VehicleView(UUID id, UUID campId, String code, String type, int maxWeightG,
            int maxVolumeCm3, boolean cold, boolean allowsHazmat, int fixedCostKrw,
            int costPerKmKrw, int costPerMinKrw, LocalTime shiftStart, LocalTime shiftEnd,
            boolean active) {
    }

    /**
     * 차량 등록 요청.
     *
     * @param campId         캠프
     * @param code           이름
     * @param type           차종
     * @param maxWeightG     최대 중량(g)
     * @param maxVolumeCm3   최대 부피(㎤)
     * @param cold           냉장 차량인가
     * @param allowsHazmat   위험물 허용인가
     * @param fixedCostKrw   고정비
     * @param costPerKmKrw   km 당 비용
     * @param costPerMinKrw  분당 비용
     * @param shiftStart     근무 시작
     * @param shiftEnd       근무 종료
     */
    public record NewVehicle(@NotNull UUID campId, @NotBlank String code, @NotBlank String type,
            @Positive int maxWeightG, @Positive int maxVolumeCm3, boolean cold,
            boolean allowsHazmat, @PositiveOrZero int fixedCostKrw,
            @PositiveOrZero int costPerKmKrw, @PositiveOrZero int costPerMinKrw,
            @NotNull LocalTime shiftStart, @NotNull LocalTime shiftEnd) {
    }

    /**
     * 기사 한 명.
     *
     * @param id        기사 id
     * @param campId    캠프
     * @param vehicleId 배정된 차량
     * @param code      이름
     * @param name      표시 이름
     * @param status    상태
     */
    public record DriverView(UUID id, UUID campId, @Nullable UUID vehicleId, String code,
            String name, String status) {
    }

    /**
     * 기사 등록 요청.
     *
     * @param campId    캠프
     * @param vehicleId 배정할 차량
     * @param code      이름
     * @param name      표시 이름
     */
    public record NewDriver(@NotNull UUID campId, @Nullable UUID vehicleId, @NotBlank String code,
            @NotBlank String name) {
    }
}
