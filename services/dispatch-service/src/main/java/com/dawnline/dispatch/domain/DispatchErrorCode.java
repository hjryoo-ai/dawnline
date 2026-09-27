package com.dawnline.dispatch.domain;

import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.ErrorCode;
import java.util.Map;
import java.util.UUID;

/**
 * dispatch-service 고유 오류 코드 (CLAUDE.md 「코딩 컨벤션」 — 서비스 고유 오류는 서비스에서 정의한다).
 *
 * <p>{@code CommonErrorCode} 에 없는 것만 둔다. 여기 있는 코드는 <strong>클라이언트가 코드만 보고 다음 행동을
 * 정할 수 있어야</strong> 의미가 있다 — 그러지 못하면 공통 코드로 충분하다.
 */
public enum DispatchErrorCode implements ErrorCode {

    /**
     * 라우트를 다시 쓰려는데 그 주문의 후보가 없다 — 보존 정리가 지웠다 (ADR-059 결정 3).
     *
     * <p>{@code conflict} 와 나누는 이유: 이 409 는 <strong>재시도해도, 룰을 고쳐도 달라지지 않는다</strong>. 계획의
     * 근거(화물 · 약속창)가 사라진 라우트는 다시 풀 수 없고, 운영자가 할 일은 그 계획을 새로 돌리는 것뿐이다. 무시하면
     * 후보 없는 stop 이 라우트에서 <em>조용히</em> 빠진다 — 이 코드가 그 자리를 소리 나게 한다.
     */
    CANDIDATES_EXPIRED("candidates-expired", 409, "계획의 후보가 보존 기간이 지나 지워졌습니다"),

    /**
     * 비활성화하려는 차량의 라우트에 끝나지 않은 stop 이 있다 (ADR-067 결정 5).
     *
     * <p>완화하지 않는다 — 배정됐지만 출발 전인 차량을 빼면 그 주문들은 주인 없이 남는다. 할 일은 그 라우트가 끝나기를
     * 기다리는 것이다(시뮬레이터는 기사가 끝낸 stop 을 dispatch 가 소비할 때까지 다시 시도한다).
     */
    VEHICLE_IN_SERVICE("vehicle-in-service", 409, "끝나지 않은 stop 이 있는 차량은 비활성화할 수 없습니다"),

    /**
     * 발행된 계획이 있는 웨이브의 함대를 재려 했다 (ADR-067 결정 2).
     *
     * <p>계획 대상 후보가 0 이 되어 답이 「부족 0」으로 보인다 — 모름을 0 으로 접지 않는다. 실패한 계획({@code FAILED})은
     * 여기 들지 않는다: 그 웨이브의 후보는 아직 계획 대상이고, 증차한 뒤 다시 돌리는 것이 운영자가 할 일이다.
     */
    WAVE_ALREADY_PLANNED("wave-already-planned", 409, "이미 계획이 발행된 웨이브입니다"),

    /**
     * 같은 코드의 차량이 이미 있다 ({@code vehicles.code} UNIQUE).
     *
     * <p>제약에 맡기면 500 이고, ops-api 는 코어의 5xx 를 「적용됐는지 모름」({@code UNKNOWN})으로 접는다 — 타임아웃 뒤
     * 다시 누른 등록이 모름을 하나 더 만든다. 409 가 <strong>있는 차량의 id</strong> 를 말하면 다시 누르기가 곧 해소다(RB-07).
     */
    VEHICLE_CODE_TAKEN("vehicle-code-taken", 409, "같은 코드의 차량이 이미 있습니다"),

    /**
     * 옮기려는 주문의 stop 이 {@code PLANNED} 가 아니다 (DESIGN.md §5.3 「재배정은 {@code PLANNED} 인 것만 옮긴다」).
     *
     * <p>끝난 stop 을 옮기면 배송된 주문이 대상 라우트의 새 {@code PLANNED} stop 으로 되살아났다 — 이 코드가 생기기 전의
     * 200 이다(§13 축 17). 취소된 주문도 같다. 본문의 {@code stopStatus} 가 지금 상태다 — 기사가 먼저 닿았으면
     * 「늦었다」이고, 할 일은 다른 주문을 고르는 것이다.
     */
    STOP_NOT_PLANNED("stop-not-planned", 409, "계획 상태가 아닌 stop 은 옮길 수 없습니다"),

    /**
     * 재배정의 받을 라우트가 끝났다 — 끝나지 않은 stop 이 없다 (DESIGN.md §5.3, ADR-068 후속 C).
     *
     * <p>「끝났다」는 보존 · 비활성화 409 · 재계획의 대상과 같은 한 조각이다. 끝난 라우트의 기사는 이미 돌아왔다 — 이 코드가 생기기
     * 전에는 200 이었고 받은 주문은 배송되지 않았다. 모든 stop 이 취소됐거나 stop 이 없는 라우트도 여기 든다.
     */
    ROUTE_FINISHED("route-finished", 409, "끝난 라우트로는 옮길 수 없습니다");

    private final String code;
    private final int status;
    private final String title;

    DispatchErrorCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }

    /**
     * 차량에 끝나지 않은 stop 이 있다.
     *
     * @param vehicleId       차량
     * @param unfinishedStops 끝나지 않은 stop 수
     * @return {@link #VEHICLE_IN_SERVICE}
     */
    public static DomainException vehicleInService(UUID vehicleId, long unfinishedStops) {
        return new DomainException(VEHICLE_IN_SERVICE,
                "차량의 라우트에 끝나지 않은 stop 이 %d 개 있습니다 — 라우트가 끝난 뒤 비활성화합니다".formatted(unfinishedStops),
                Map.of("vehicleId", vehicleId.toString(), "unfinishedStops", unfinishedStops));
    }

    /**
     * 웨이브에 발행된 계획이 있다.
     *
     * @param waveId 웨이브
     * @param planId 그 계획
     * @return {@link #WAVE_ALREADY_PLANNED}
     */
    public static DomainException waveAlreadyPlanned(UUID waveId, UUID planId) {
        return new DomainException(WAVE_ALREADY_PLANNED,
                "계획이 이미 발행돼 계획 대상 후보가 없습니다 — 함대 판정은 계획 전의 것입니다",
                Map.of("waveId", waveId.toString(), "planId", planId.toString()));
    }

    /**
     * 같은 코드의 차량이 있다.
     *
     * @param code      코드
     * @param vehicleId 있는 차량
     * @return {@link #VEHICLE_CODE_TAKEN}
     */
    public static DomainException vehicleCodeTaken(String code, UUID vehicleId) {
        return new DomainException(VEHICLE_CODE_TAKEN, "코드 %s 의 차량이 이미 있습니다".formatted(code),
                Map.of("code", code, "vehicleId", vehicleId.toString()));
    }

    /**
     * 옮기려는 주문의 stop 이 {@code PLANNED} 가 아니다.
     *
     * @param routeId    옮기려던 라우트
     * @param orderId    주문
     * @param stopStatus 지금 상태 — 그 주문이 취소됐으면 {@code CANCELLED}
     * @return {@link #STOP_NOT_PLANNED}
     */
    public static DomainException stopNotPlanned(UUID routeId, UUID orderId, RouteStopStatus stopStatus) {
        return new DomainException(STOP_NOT_PLANNED,
                "주문의 stop 이 %s 라 옮길 수 없습니다 — PLANNED 인 것만 옮깁니다".formatted(stopStatus),
                Map.of("routeId", routeId.toString(), "orderId", orderId.toString(), "stopStatus", stopStatus.name()));
    }

    /**
     * 받을 라우트가 끝났다.
     *
     * @param routeId 받을 라우트
     * @return {@link #ROUTE_FINISHED}
     */
    public static DomainException routeFinished(UUID routeId) {
        return new DomainException(ROUTE_FINISHED,
                "받을 라우트에 끝나지 않은 stop 이 없습니다 — 기사가 이미 돌아왔습니다",
                Map.of("routeId", routeId.toString()));
    }

    /**
     * 라우트의 주문 가운데 후보가 없는 것이 있다.
     *
     * @param routeId 다시 쓰려던 라우트
     * @param orderId 후보가 없는 주문 하나
     * @return {@link #CANDIDATES_EXPIRED}
     */
    public static DomainException candidatesExpired(UUID routeId, UUID orderId) {
        return new DomainException(CANDIDATES_EXPIRED,
                "후보가 지워진 주문이 있어 라우트를 다시 쓸 수 없습니다 — 계획을 새로 돌려야 합니다",
                Map.of("routeId", routeId.toString(), "orderId", orderId.toString()));
    }
}
