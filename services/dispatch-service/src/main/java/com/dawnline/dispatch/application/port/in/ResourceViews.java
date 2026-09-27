package com.dawnline.dispatch.application.port.in;

import com.dawnline.common.fleet.FleetFeasibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
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
     * @param source         누가 넣었나 — {@code seed} · {@code operator} · {@code peak-sim} (V11, ADR-067 결정 4)
     */
    public record VehicleView(UUID id, UUID campId, String code, String type, int maxWeightG,
            int maxVolumeCm3, boolean cold, boolean allowsHazmat, int fixedCostKrw,
            int costPerKmKrw, int costPerMinKrw, LocalTime shiftStart, LocalTime shiftEnd,
            boolean active, String source) {
    }

    /**
     * 차량 등록 요청.
     *
     * @param campId         캠프
     * @param code           이름 — {@code VARCHAR(16)}. 길이를 여기서 막지 않으면 DB 가 거절하고 그것은 500 이다
     * @param type           차종 — {@code VARCHAR(16)}
     * @param maxWeightG     최대 중량(g)
     * @param maxVolumeCm3   최대 부피(㎤)
     * @param cold           냉장 차량인가
     * @param allowsHazmat   위험물 허용인가
     * @param fixedCostKrw   고정비
     * @param costPerKmKrw   km 당 비용
     * @param costPerMinKrw  분당 비용
     * @param shiftStart     근무 시작
     * @param shiftEnd       근무 종료
     * @param source         누가 넣었나. 생략하면 {@code operator}. {@code seed} 는 받지 않는다 — 시드는 마이그레이션만 쓴다
     *                       (ADR-067 결정 4)
     */
    public record NewVehicle(@NotNull UUID campId, @NotBlank @Size(max = 16) String code,
            @NotBlank @Size(max = 16) String type,
            @Positive int maxWeightG, @Positive int maxVolumeCm3, boolean cold,
            boolean allowsHazmat, @PositiveOrZero int fixedCostKrw,
            @PositiveOrZero int costPerKmKrw, @PositiveOrZero int costPerMinKrw,
            @NotNull LocalTime shiftStart, @NotNull LocalTime shiftEnd,
            @Nullable @Pattern(regexp = SOURCES) String source) {

        /** 운영자가 적을 수 있는 출처 — 닫힌 집합(V11 의 CHECK)에서 {@code seed} 를 뺀 것. */
        public static final String SOURCES = "operator|peak-sim";

        /** 생략하면 운영자의 것이다. */
        public static final String DEFAULT_SOURCE = "operator";

        /** 적을 출처 — 생략했으면 {@link #DEFAULT_SOURCE}. */
        public String effectiveSource() {
            return source == null ? DEFAULT_SOURCE : source;
        }
    }

    /**
     * 웨이브의 함대 실현 가능성 (DESIGN.md §5.3 「함대」, ADR-067 결정 2).
     *
     * @param waveId           웨이브
     * @param campId           캠프
     * @param assessedAt       잰 시각(주입 시계) — 계획이 근무창을 붙이는 기준도 이 시각이다
     * @param candidates       계획 대상 후보 수
     * @param stops            통합 후 stop 수 — 기준은 이것으로 잰다
     * @param fleet            계획이 쓸 수 있는 차량 수(활성 · 근무창이 약속창과 겹친다)
     * @param maxStopsPerRoute stop 축의 상한(룰의 {@code max-stops}). {@code null} 이면 재지 않았다
     * @param headroomPercent  여유 — 수요는 용량의 이 퍼센트 이하여야 한다
     * @param feasible         모든 조합이 여유 안이다
     * @param combinations     조합마다 한 줄, 가장 특정한 조합부터(잰 순서)
     */
    public record FleetFeasibilityView(UUID waveId, UUID campId, Instant assessedAt, int candidates, int stops,
            int fleet, @Nullable Integer maxStopsPerRoute, int headroomPercent, boolean feasible,
            List<FleetLineView> combinations) {
    }

    /**
     * 조합 한 줄.
     *
     * @param cold              냉장
     * @param hazmat            위험물
     * @param large             대형 — 가장 작은 가용 차량에 들어가지 않는 stop
     * @param label             사람이 읽을 이름
     * @param status            {@code FEASIBLE} · {@code SHORTFALL} · {@code NO_TEMPLATE}
     * @param demandStops       이 조합을 최소한 요구하는 stop 수
     * @param demandWeightG     그 중량(g)
     * @param demandVolumeCm3   그 부피(㎤)
     * @param vehicles          이 조합을 모두 갖춘 차량 수
     * @param capacityStops     그 stop 슬롯(차량 수 × 상한, 상한이 없으면 0)
     * @param capacityWeightG   그 중량 용량(g)
     * @param capacityVolumeCm3 그 부피 용량(㎤)
     * @param shortfall         더할 대수 — 앞 조합에서 더한 차량을 센 뒤다. {@code NO_TEMPLATE} 이면 {@code null}
     * @param template          더할 차량의 원본. 없으면 {@code null} — 부족한데 없으면 {@code NO_TEMPLATE}
     */
    public record FleetLineView(boolean cold, boolean hazmat, boolean large, String label,
            FleetFeasibility.Status status, long demandStops, long demandWeightG, long demandVolumeCm3,
            int vehicles, long capacityStops, long capacityWeightG, long capacityVolumeCm3,
            @Nullable Integer shortfall, @Nullable VehicleView template) {
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
