package com.dawnline.dispatch.application.port.out;

import com.dawnline.dispatch.domain.RouteStopStatus;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.Stop;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 라우트를 고치는 최소한의 연산 (DESIGN.md §5.3 운영자 재배정).
 *
 * <p>애그리거트를 되살리지 않는다. 라우트는 120 stop 까지 가고 여기서 바뀌는 것은
 * <strong>주문 하나의 소속</strong>뿐이다.
 */
public interface RouteMutations {

    /**
     * 라우트의 소속 계획과 차량.
     *
     * @param routeId 라우트 id
     */
    Optional<RouteHeader> findHeader(UUID routeId);

    /**
     * 라우트의 stop 들을 방문 순서대로 되살린다.
     *
     * <p>화물·약속창은 {@code route_stops} 에 없고 {@code dispatch_candidates} 에 있다 —
     * 계획의 근거는 후보이고 라우트는 그 결과이기 때문이다. 룰을 다시 돌리려면 근거가 필요하다.
     *
     * <p><strong>취소된 것은 빠진다</strong> — 취소된 stop 도, 살아 있는 stop 에 섞인 취소된
     * 주문도. 기사가 건너뛸 지점을 계산에 넣으면 그 지점까지의 이동 시간이 남은 stop 의 도착
     * 시각을 뒤로 민다 (§6.10). 취소된 것을 <em>발행</em>에서 지우지 않는 것과 계산에서 빼는
     * 것은 다른 일이다 — 발행은 {@link #snapshot} 이 만든다.
     *
     * @param routeId 라우트 id
     */
    default List<Stop> loadStops(UUID routeId) {
        return loadPositionedStops(routeId).stream().map(PositionedStop::stop).toList();
    }

    /**
     * {@link #loadStops} 와 <strong>같은 목록</strong>에 순번을 붙인 것 (§6.8, ADR-048 결정 4).
     *
     * <p>재계획은 「얼어 있는 앞자락이 몇 개인가」를 알아야 하고, 그것은 <em>저장된 순번</em>과
     * <em>이 목록의 자리</em>를 잇는 일이다. 두 목록을 따로 두지 않고 이쪽이 원본이고 위쪽이
     * 파생인 이유가 그것이다 — 「A 와 B 는 같은 내용」이라고 <em>적어 둔</em> 두 곳은 그 문장이
     * 아직 참인지 아무도 묻지 않으면 갈라지고, 갈라진 쪽은 빈자리라서 눈에 띄지 않는다
     * (CLAUDE.md 코딩 컨벤션). 파생으로 두면 갈라질 자리가 없다.
     *
     * @param routeId 라우트 id
     */
    List<PositionedStop> loadPositionedStops(UUID routeId);

    /**
     * 이 계획의 라우트들 — {@code seq_no} 순서 (§6.8 2단계).
     *
     * <p>재계획의 후보 차량이다. 같은 계획 안이므로 캠프도 같고, 「여유 용량이 있는 진행 중
     * 라우트」와 「미출발 차량」의 구분은 <strong>상태 칼럼이 아니라 사실</strong>로 한다 —
     * 닿은 stop 이 있으면 떠난 것이다({@link #lastSettledStop}). {@code routes.status} 는
     * 저장 시점의 값이고 출발을 알리는 이벤트가 없다(§5.4 {@code DEPARTED_CAMP} 는 브로커로
     * 나가지 않는다).
     *
     * @param planId 계획 id
     */
    List<RouteHeader> routesOfPlan(UUID planId);

    /**
     * 이 주문이 실린 stop.
     *
     * @param routeId 라우트 id
     * @param orderId 주문 id
     */
    Optional<UUID> findStopOf(UUID routeId, UUID orderId);

    /**
     * 주문을 다른 라우트로 옮긴다. 목적지에 같은 지점의 stop 이 없으면 새로 만든다.
     *
     * @param fromStopId    떠나는 stop
     * @param orderId       주문
     * @param targetRouteId 도착 라우트
     */
    void moveOrder(UUID fromStopId, UUID orderId, UUID targetRouteId);

    /**
     * 이동 뒤 라우트를 다시 쓴다 — 순번 재부여, 시간 재전파, 요약 갱신.
     *
     * <p>시각과 비용은 <strong>도메인이 계산해서 넘긴다</strong>. 어댑터가 다시 계산하면 그
     * 계산이 두 곳이 되고, 두 곳은 갈라진다.
     *
     * @param routeId 라우트 id
     * @param route   다시 계산된 라우트. 비어 있으면 stop 을 전부 지운다
     */
    void rewrite(UUID routeId, PlannedRoute route);

    /**
     * 라우트를 비운다 (마지막 주문이 떠난 경우).
     *
     * @param routeId 라우트 id
     */
    void clear(UUID routeId);

    /**
     * 개정 번호를 올리고 새 값을 돌려준다 (§6.8 4단계).
     *
     * @param routeId 라우트 id
     */
    int bumpRevision(UUID routeId);

    /**
     * 이 주문이 실린 stop — 라우트를 모르는 채로 찾는다 (§6.10).
     *
     * <p>취소는 {@code orderId} 만 들고 온다. 그 주문이 이미 발행된 라우트에 실려 있는지가
     * 분기를 가르므로(ADR-026 결정 2) 여기서 한 번에 찾는다.
     *
     * @param orderId 주문 id
     */
    Optional<AssignedStop> findAssignedStop(UUID orderId);

    /**
     * 이 stop 의 주문이 <strong>전부</strong> 취소됐으면 stop 을 취소로 표시한다.
     *
     * <p>일부만 취소된 stop 은 여전히 방문한다 — 남은 주문을 배송해야 한다. 그래서 이 판정은
     * "stop 이 죽었는가" 이고 "주문이 죽었는가" 가 아니다
     * (ADR-026 [후속 정정 — Phase 3-6]).
     *
     * @param stopId stop id
     * @return 이 호출로 stop 이 취소됐으면 참
     */
    boolean cancelStopIfAllOrdersCancelled(UUID stopId);

    /**
     * 순서를 그대로 두고 시각만 다시 쓴다 (§6.10 — 재시퀀싱하지 않는다).
     *
     * <p>{@link #rewrite} 와 다른 점이 이것 하나다. 취소는 기사가 이미 보고 있는 순번을 바꾸지
     * 않으므로 {@code seq} 에 손대지 않고, 건너뛴 stop 만큼 뒤의 도착 시각이 앞으로 온다.
     *
     * @param routeId 라우트 id
     * @param route   살아 있는 stop 만으로 다시 계산한 라우트. 하나도 없으면 {@code null}
     */
    void retime(UUID routeId, @Nullable PlannedRoute route);

    /**
     * 저장된 그대로의 라우트 — <strong>취소된 stop 을 포함</strong>한다.
     *
     * <p>개정 발행의 입력이다. 계획 결과가 아니라 저장된 상태에서 만드는 이유는
     * {@link RouteSnapshot} 의 주석에 있다.
     *
     * @param routeId 라우트 id
     */
    Optional<RouteSnapshot> snapshot(UUID routeId);

    /**
     * 이 stop 의 상태를 옮긴다 (ADR-047).
     *
     * <p>판정은 {@link com.dawnline.dispatch.domain.RouteStopTransition} 이 이미 내렸다 —
     * 여기서는 한 행을 쓸 뿐이다. 조건부 갱신(«현재 상태가 X 일 때만»)을 걸지 않는 이유는
     * 그 조건이 <em>규칙</em>이고 규칙은 한 곳에만 있어야 하기 때문이다. 같은 트랜잭션 안에서
     * 읽고 쓰므로 그 사이에 끼어들 수 있는 것은 다른 트랜잭션이고, 그건 행 잠금이 막는다.
     *
     * <p>{@code actualAt} 은 <strong>이 행이 아직 비어 있을 때만</strong> 쓴다 — 그 stop 에
     * <em>처음</em> 닿은 시각이기 때문이다(ADR-048 결정 1). 덮어쓰면 이 값은 도착이 아니라
     * 완료가 되고, §6.8 의 편차가 「얼마나 늦게 도착했나」에서 「거기서 머문 시간까지 더한 값」
     * 으로 조용히 바뀐다. 그 변화는 <strong>값을 보아서는 알 수 없다.</strong>
     *
     * @param stopId   stop id
     * @param status   새 상태
     * @param actualAt 그 stop 에 닿은 시각 ({@code delivery.status} 의 {@code occurredAt})
     */
    void markStopStatus(UUID stopId, RouteStopStatus status, Instant actualAt);

    /**
     * 재계획 쿨다운을 <strong>한 문장으로</strong> 집는다 (§6.8 5단계, ADR-046 결정 3).
     *
     * <p>읽고 나서 쓰면 두 소비자가 같은 값을 읽는 창이 생긴다. 비교와 갱신이 한
     * {@code UPDATE} 여야 하고, 그것이 이 메서드가 포트에 있는 이유다 — 「최근 재계획 시각을
     * 돌려준다」로 두면 그 창이 호출부로 옮겨갈 뿐이다.
     *
     * <p>멱등 소비자는 이 자리를 대신하지 못한다. 두 {@code delivery.at-risk} 는 서로 다른
     * {@code eventId} 라 {@code processed_events} 에게는 둘 다 처음 보는 이벤트다.
     *
     * @param routeId  라우트 id
     * @param now      지금 (주입된 시계, 불변규칙 12)
     * @param cooldown 쿨다운 길이
     * @return 이 호출이 쿨다운을 집었으면 참. 거짓이면 그 안에 이미 재계획이 돌았다
     */
    boolean tryStartReplan(UUID routeId, Instant now, Duration cooldown);

    /**
     * 이 라우트에서 기사가 <strong>가장 멀리 닿은</strong> stop (§6.8, ADR-048 결정 1).
     *
     * <p>「마지막」의 기준은 {@code actual_at} 이 아니라 {@code seq} 다. 순서가 뒤바뀌어 도착한
     * 상태 보고가 있어도 기사가 서 있는 자리는 <em>순번이 가장 큰</em> 닿은 stop 이고, §6.8 의
     * 「얼어 있는 앞자락」이 바로 거기까지다.
     *
     * @param routeId 라우트 id
     * @return 아직 아무 데도 닿지 않았으면 빈 값 — <strong>편차 0 이 아니라 «모름» 이다</strong>
     */
    Optional<SettledStop> lastSettledStop(UUID routeId);

    /**
     * 주문이 실린 stop 과 그 stop 의 상태.
     *
     * @param routeId 라우트 id
     * @param stopId  stop id
     * @param seq     방문 순번. <strong>조회 키가 아니다</strong> — {@code delivery.status} 가
     *                싣고 온 순번과 다른지 보는 데만 쓴다(ADR-047 결정 1, 기각 (1))
     * @param status  {@code route_stops.status}
     */
    record AssignedStop(UUID routeId, UUID stopId, int seq, RouteStopStatus status) {

        /** 기사가 이미 그 지점에 닿았는가. 닿았으면 취소는 거부된다 (ADR-026 결정 2 네 번째 행). */
        public boolean visited() {
            return status.visited();
        }
    }

    /**
     * 라우트의 머리 정보.
     *
     * @param routeId   라우트 id
     * @param planId    소속 계획
     * @param vehicleId 차량
     */
    record RouteHeader(UUID routeId, UUID planId, UUID vehicleId) {
    }

    /**
     * stop 하나와 그 저장된 순번.
     *
     * @param seq  {@code route_stops.seq}. 취소된 stop 이 빠져 있으므로 <strong>연속이 아닐 수
     *             있다</strong> — 목록의 자리와 다른 값이고, 그래서 따로 든다
     * @param stop 계산에 쓰는 stop
     */
    record PositionedStop(int seq, Stop stop) {

        public PositionedStop {
            Objects.requireNonNull(stop, "stop");
        }
    }

    /**
     * 기사가 닿은 stop 하나 — 계획과 사실을 나란히 든다 (ADR-048 결정 1).
     *
     * @param seq            방문 순번. §6.8 의 「얼어 있는 앞자락」이 여기까지다
     * @param plannedArrival 계획 도착 시각. 재계획을 지나도 움직이지 않는다 — 저장 시계가 계획
     *                       시계 그대로이기 때문이고, 그 안정성이 아래 편차를 성립시킨다
     * @param actualAt       실제로 닿은 시각
     */
    record SettledStop(int seq, Instant plannedArrival, Instant actualAt) {

        public SettledStop {
            Objects.requireNonNull(plannedArrival, "plannedArrival");
            Objects.requireNonNull(actualAt, "actualAt");
        }

        /** 편차 — 이르면 음수다. §6.8 이 평가 시계를 미는 값이고, 페이로드의 값과 견주는 값이다. */
        public Duration deviation() {
            return Duration.between(plannedArrival, actualAt);
        }
    }
}
