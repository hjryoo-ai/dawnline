package com.dawnline.sim.fleet;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * ops-api 가운데 함대 단계가 부르는 것 (DESIGN.md §5.5 「커맨드 위임」 · 「조회」, ADR-067).
 *
 * <p>조회는 실패하면 {@link OpsException} 이다 — 전제 · 기준 · 계획을 읽지 못한 채 실행을 이어 가면 그 실행의 수가 무엇을 말하는지
 * 모른다. 커맨드는 예외 대신 {@link Reply} 로 답한다 — 거절(409)이 정상 흐름인 자리(비활성화의 {@code vehicle-in-service})가 있다.
 */
public interface OpsClient {

    /** @return 읽기 모델에 웨이브가 있는 캠프 — {@code GET /camps} */
    List<UUID> camps();

    /**
     * @param campId 캠프
     * @param from   컷오프 창 시작
     * @param to     컷오프 창 끝
     * @return {@code GET /camps/{campId}/waves}
     */
    List<Wave> waves(UUID campId, Instant from, Instant to);

    /**
     * @param waveId 웨이브
     * @return dispatch 의 함대 판정 — {@code GET /waves/{waveId}/fleet-feasibility}
     */
    Assessment fleetFeasibility(UUID waveId);

    /**
     * @param campId 캠프
     * @return dispatch 의 캠프 차량 — {@code GET /vehicles?campId=}
     */
    List<Vehicle> vehicles(UUID campId);

    /**
     * @param vehicle 등록 본문
     * @return {@code POST /vehicles} — {@code ADD_VEHICLE} 감사 행
     */
    Reply addVehicle(NewVehicle vehicle);

    /**
     * @param vehicleId 차량
     * @return {@code POST /vehicles/{vehicleId}/deactivate} — {@code DEACTIVATE_VEHICLE} 감사 행
     */
    Reply deactivate(UUID vehicleId);

    /**
     * 읽기 모델의 웨이브 한 행.
     *
     * @param waveId          웨이브
     * @param serviceTier     등급
     * @param cutoffAt        컷오프
     * @param status          {@code OPEN} · {@code CLOSED} · {@code PLAN_FAILED} · {@code PLANNED}
     * @param orderCount      주문 수(집계)
     * @param unassignedCount 미배정 — {@code plan.completed}
     * @param routeCount      라우트 수 — 기사가 기다릴 수
     * @param closedAt        실제 마감 시각 — 시간 예산의 기준(V9)
     */
    record Wave(UUID waveId, @Nullable String serviceTier, @Nullable Instant cutoffAt, @Nullable String status,
            @Nullable Integer orderCount, @Nullable Integer unassignedCount, @Nullable Integer routeCount,
            @Nullable Instant closedAt) {
    }

    /**
     * 함대 판정.
     *
     * @param waveId           웨이브
     * @param campId           캠프
     * @param assessedAt       잰 시각
     * @param candidates       계획 대상 후보 수 — 계산의 기준
     * @param stops            통합 후 stop 수
     * @param fleet            계획이 쓸 수 있는 차량 수
     * @param maxStopsPerRoute stop 축의 상한
     * @param feasible         모든 조합이 여유 안
     * @param combinations     조합마다 한 줄, 가장 특정한 조합부터
     */
    record Assessment(UUID waveId, UUID campId, Instant assessedAt, int candidates, int stops, int fleet,
            @Nullable Integer maxStopsPerRoute, boolean feasible, List<Line> combinations) {
        public Assessment {
            combinations = List.copyOf(combinations);
        }
    }

    /**
     * 조합 한 줄.
     *
     * @param label     사람이 읽을 이름
     * @param status    {@code FEASIBLE} · {@code SHORTFALL} · {@code NO_TEMPLATE}
     * @param vehicles  이 조합을 모두 갖춘 차량 수
     * @param shortfall 더할 대수 — {@code NO_TEMPLATE} 이면 {@code null}
     * @param template  더할 차량의 원본
     */
    record Line(String label, String status, int vehicles, @Nullable Integer shortfall, @Nullable Vehicle template) {

        /** 부족한데 더할 템플릿이 없다 — 값이 아니다(ADR-067 결정 3). */
        public boolean noTemplate() {
            return "NO_TEMPLATE".equals(status);
        }

        /** 더할 대수. 부족하지 않으면 0. */
        public int toAdd() {
            return "SHORTFALL".equals(status) && shortfall != null ? shortfall : 0;
        }
    }

    /**
     * dispatch 의 차량.
     *
     * @param id            차량
     * @param campId        캠프
     * @param code          이름
     * @param type          차종
     * @param maxWeightG    최대 중량(g)
     * @param maxVolumeCm3  최대 부피(㎤)
     * @param cold          냉장
     * @param allowsHazmat  위험물 허용
     * @param fixedCostKrw  고정비
     * @param costPerKmKrw  km 당 비용
     * @param costPerMinKrw 분당 비용
     * @param shiftStart    근무 시작
     * @param shiftEnd      근무 종료
     * @param active        가용
     * @param source        출처
     */
    record Vehicle(UUID id, UUID campId, String code, String type, int maxWeightG, int maxVolumeCm3, boolean cold,
            boolean allowsHazmat, int fixedCostKrw, int costPerKmKrw, int costPerMinKrw, LocalTime shiftStart,
            LocalTime shiftEnd, boolean active, String source) {
    }

    /**
     * 차량 등록 본문 — ops-api 의 {@code VehicleBody}.
     *
     * @param campId        캠프
     * @param code          이름 — 16자 이하
     * @param type          차종
     * @param maxWeightG    최대 중량(g)
     * @param maxVolumeCm3  최대 부피(㎤)
     * @param cold          냉장
     * @param allowsHazmat  위험물 허용
     * @param fixedCostKrw  고정비
     * @param costPerKmKrw  km 당 비용
     * @param costPerMinKrw 분당 비용
     * @param shiftStart    근무 시작
     * @param shiftEnd      근무 종료
     * @param source        {@code peak-sim}
     */
    record NewVehicle(UUID campId, String code, String type, int maxWeightG, int maxVolumeCm3, boolean cold,
            boolean allowsHazmat, int fixedCostKrw, int costPerKmKrw, int costPerMinKrw, LocalTime shiftStart,
            LocalTime shiftEnd, String source) {

        /** 템플릿의 사본 — 코드와 출처만 다르다(ADR-067 결정 3). */
        static NewVehicle copyOf(Vehicle template, String code, String source) {
            return new NewVehicle(template.campId(), code, template.type(), template.maxWeightG(),
                    template.maxVolumeCm3(), template.cold(), template.allowsHazmat(), template.fixedCostKrw(),
                    template.costPerKmKrw(), template.costPerMinKrw(), template.shiftStart(), template.shiftEnd(),
                    source);
        }
    }

    /**
     * 커맨드의 답.
     *
     * @param status  HTTP 상태
     * @param auditId 감사 행 id — {@code X-Dawnline-Audit-Id}. 커맨드면 모든 결과에 온다
     * @param code    Problem Details 의 {@code code} — 거절일 때
     * @param id      2xx 본문의 {@code id} — 등록이면 새 차량, 비활성화면 그 차량
     */
    record Reply(int status, @Nullable String auditId, @Nullable String code, @Nullable UUID id) {

        /** 2xx. */
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** 조회가 답하지 못했다 — 상태와 코드를 싣는다. */
    final class OpsException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** HTTP 상태. 닿지 못했으면 0. */
        private final int status;

        /** Problem Details 의 {@code code}. */
        private final @Nullable String code;

        /**
         * @param message 무엇을 읽다가
         * @param status  상태 — 닿지 못했으면 0
         * @param code    Problem Details 의 {@code code}
         */
        public OpsException(String message, int status, @Nullable String code) {
            super(message + " — " + (status == 0 ? "ops-api 에 닿지 못했다" : "HTTP " + status)
                    + (code == null ? "" : " " + code));
            this.status = status;
            this.code = code;
        }

        /** @return HTTP 상태 */
        public int status() {
            return status;
        }

        /** @return Problem Details 의 {@code code} */
        public @Nullable String code() {
            return code;
        }
    }
}
