package com.dawnline.sim.driver;

import com.dawnline.sim.driver.AssignedRoute.PlannedStop;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 기사 한 명이 라우트를 어떻게 도는가 — <strong>순수 함수</strong> (IMPLEMENTATION_PLAN Phase 5-2).
 *
 * <p>HTTP 도 Kafka 도 시계도 없다. 입력은 개정 하나와 현재 위치, 출력은 남은 스캔 열이다.
 * 그래서 「이 라우트를 이렇게 돈다」를 서버 없이 어설션할 수 있고, 개정이 오면 같은 함수를
 * 새 위치로 다시 부르기만 하면 된다.
 *
 * <h2>시간 모형</h2>
 * 계획에서 파생한다. {@code Instant.now()} 를 부르지 않는다 (불변규칙 12).
 *
 * <pre>
 *   실제 출발      = plannedDeparture + 주입한 출발 지연
 *   계획 이동 시간  = max(0, plannedArrival − 직전 방문 지점의 계획 완료 시각)
 *   실제 도착      = 직전 실제 완료 + 계획 이동 시간 × (1 + 주입한 지연 비율)
 *   실제 완료      = 실제 도착 + serviceSeconds
 * </pre>
 *
 * <p>그래서 <strong>주입한 지연이 곧 tracking 이 계산하는 편차</strong>다 — 「실제 도착 −
 * 계획 도착」이 우리가 넣은 값이고, {@code late-injection} 은 그것을 어설션할 수 있다.
 *
 * <p>{@code max(0, …)} 로 자르는 이유는 두 가지다. 계획이 약속창을 기다리느라 비워 둔 구간은
 * 음수가 아니라 0 이고, 개정이 순서를 뒤집어 앵커가 뒤로 가는 경우도 0 이다 — 시뮬레이터가
 * 시간을 되돌리면 {@code occurredAt} 이 단조 증가하지 않고, 그 순간 tracking 의 상태 머신이
 * 보는 것은 <em>지연</em>이 아니라 <em>역행</em>이 된다.
 *
 * <h2>건너뛰는 stop 둘은 다른 것이다</h2>
 * <ul>
 *   <li><strong>취소된 stop</strong>({@code status: CANCELLED})은 애초에 가지 않는다. 계획
 *       앵커도 옮기지 않는다 — 가지 않은 지점에서 체류할 수 없다.</li>
 *   <li><strong>이미 끝낸 stop</strong>(그 주문이 전부 {@link TripProgress#completedOrders()} 에
 *       있다)은 <em>갔던</em> 곳이므로 앵커를 옮긴다. 그리고 새 개정이 뭐라 하든 다시 스캔하지
 *       않는다 — tracking 의 「개정은 종결을 되돌리지 않는다」와 대칭이다.</li>
 * </ul>
 */
public final class DriverSimulator {

    private final Jitter jitter;

    /**
     * @param jitter 지연·실패 주입. seed 에서 뽑는다 (불변규칙 12)
     */
    public DriverSimulator(Jitter jitter) {
        this.jitter = Objects.requireNonNull(jitter, "jitter");
    }

    /**
     * 이 개정을 현재 위치에서 끝까지 도는 스캔 열 — <strong>취소된 stop 은 가지 않은 곳이라
     * 체류가 없고, 이미 끝낸 stop 은 갔던 곳이라 계획 앵커를 옮긴다.</strong>
     *
     * <p>그 한 문장이 개정 뒤 시각 계산의 전부다. 앵커는 「직전 방문 지점의 계획 완료 시각」이고,
     * 다음 구간의 계획 이동 시간은 거기서부터 잰다. 가지 않은 지점에서 체류할 수 없으므로 취소된
     * stop 은 앵커를 그대로 두고(그만큼 다음 구간이 길어진다), 갔던 지점은 실제로 시간을 썼으므로
     * 앵커를 옮긴다(그래야 재계획 뒤의 첫 구간이 캠프부터 다시 재지 않는다).
     *
     * @param route    개정 하나
     * @param progress 현재 위치. 처음이면 {@link TripProgress#start()}
     * @return 보낼 순서대로의 스캔. {@code occurredAt} 은 단조 증가한다
     * @throws IllegalStateException 계획 시각이 없는 이벤트면 ({@link AssignedRoute#requireDrivable()})
     */
    public List<ScanCall> remainingCalls(AssignedRoute route, TripProgress progress) {
        route.requireDrivable();

        List<ScanCall> calls = new ArrayList<>();
        Instant plannedAnchor = route.plannedDeparture();
        Instant actual;

        Instant readyAt = progress.readyAt();
        if (readyAt == null) {
            actual = route.plannedDeparture()
                    .plusSeconds(jitter.departureDelaySeconds(route.routeId(), route.revision()));
            // 캠프 좌표는 route.assigned 에 없다. 위치를 모르는 것과 보내지 않는 것은 같고,
            // 단말도 위치를 못 잡으면 비워 보낸다 (ScanRequest.lat 의 계약).
            calls.add(new ScanCall(route.stops().getFirst().seq(), ScanType.DEPARTED_CAMP,
                    actual, null, null, null));
        } else {
            actual = readyAt;
        }

        for (PlannedStop stop : route.stops()) {
            Instant plannedArrival = Objects.requireNonNull(stop.plannedArrival());
            Instant plannedFinish = plannedArrival.plusSeconds(Objects.requireNonNull(stop.serviceSeconds()));

            if (stop.isCancelled()) {
                continue;
            }
            if (isDone(stop, progress)) {
                plannedAnchor = plannedFinish;
                continue;
            }

            long legSeconds = Math.max(0L, Duration.between(plannedAnchor, plannedArrival).toSeconds());
            double factor = jitter.delayFactor(route.routeId(), route.revision(), stop.seq());
            Instant arrival = actual.plusSeconds(Math.round(legSeconds * (1.0 + factor)));
            calls.add(new ScanCall(stop.seq(), ScanType.ARRIVED, arrival, stop.lat(), stop.lng(), null));

            Instant finished = arrival.plusSeconds(stop.serviceSeconds());
            boolean fails = jitter.fails(route.routeId(), route.revision(), stop.seq());
            calls.add(fails
                    ? new ScanCall(stop.seq(), ScanType.FAILED, finished, stop.lat(), stop.lng(),
                            jitter.failureReason(route.routeId(), route.revision(), stop.seq()))
                    : new ScanCall(stop.seq(), ScanType.COMPLETED, finished, stop.lat(), stop.lng(), null));

            actual = finished;
            plannedAnchor = plannedFinish;
        }
        return List.copyOf(calls);
    }

    /**
     * 이 stop 의 주문이 전부 끝났는가.
     *
     * <p>「하나라도 남았으면 간다」가 맞는 방향이다 — 개정이 이미 배송한 주문과 새 주문을 같은
     * 지점에 합쳐 놓았을 때, 가지 않으면 새 주문이 조용히 배달되지 않는다. 그 반대(한 번 더
     * 스캔)는 tracking 이 {@code STALE} 로 흡수한다.
     */
    private static boolean isDone(PlannedStop stop, TripProgress progress) {
        return !stop.orderIds().isEmpty() && progress.completedOrders().containsAll(stop.orderIds());
    }
}
