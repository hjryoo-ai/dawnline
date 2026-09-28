package com.dawnline.tracking.application;

import com.dawnline.tracking.application.port.out.RouteRevisions;
import com.dawnline.tracking.application.port.out.ShipmentRepository;
import com.dawnline.tracking.domain.ScanType;
import com.dawnline.tracking.domain.Shipment;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * 편차를 라우트 행에 적는다 (DESIGN.md §5.4 ETA 재계산,
 * [ADR-070](docs/adr/ADR-070-tracking-writes-lock-the-route-first.md) 결정 2).
 *
 * <h2>왜 애그리거트 밖인가</h2>
 * 편차는 <strong>라우트의 성질</strong>이다 — 어느 stop 에서 얼마가 벌어졌고, 그것이 누구에게
 * 옮겨 가는지는 <em>방문 순서를 아는 쪽</em>만 안다. {@link Shipment} 는 주문 하나만 알고, 받는 것은
 * 편차 하나다({@link Shipment#etaWith}).
 *
 * <h2>적는 곳은 라우트 행 하나다</h2>
 * 예전에는 뒤따르는 배송마다 {@code eta_at} 을 옮겨 적었다 — 스캔 하나가 뒤 stop 전부를 다시 써서 라우트당 O(n²) 였고
 * (30-stop 라우트 하나에 {@code shipments} 갱신 990, 근거: 관측(재현됨)), {@code shipments} 의 HOT 0 과 tracking 풀 대기가 그 자리였다.
 * 지금은 {@code route_revisions.deviation_seconds} 에 한 번 적고, ETA 는 읽는 자리(at-risk 판정 · 페이로드)가 planned + 편차로 계산한다.
 * 부르는 쪽이 그 행을 이미 잡았다(쓰기 계층 라우트 행 → {@code shipments}, 결정 1).
 *
 * <h2>기준값이 스캔 종류마다 다르다</h2>
 * <ul>
 *   <li>{@code DEPARTED_CAMP} — {@code route_revisions.planned_departure}. 늦은 출발은 가장 흔한 지연 원인이고
 *       첫 도착 스캔 <em>전에</em> 이미 알 수 있다 — 이 갈래가 없으면 위험 감지가 첫 배송까지 늦는다.</li>
 *   <li>그 밖 — 스캔이 난 stop 의 {@code planned_arrival}.</li>
 * </ul>
 *
 * <h2>부호를 지우지 않는다</h2>
 * 편차는 음수일 수 있고(일찍 도착) 그대로 적는다. 「늦은 것만 민다」로 적으면 앞서 가는
 * 라우트의 ETA 가 영원히 낡은 채로 남고, ops 화면이 그 값을 읽는다.
 */
public class EtaPropagator {

    /** 라우트의 첫 stop. {@code DEPARTED_CAMP} 가 보는 범위의 시작이다. */
    private static final int FIRST_SEQ = 1;

    private final ShipmentRepository shipments;
    private final RouteRevisions revisions;

    /**
     * @param shipments 배송 저장소
     * @param revisions 라우트당 한 행 — 계획 출발 시각의 출처이자 편차를 적는 자리다 (ADR-045 · ADR-070)
     */
    public EtaPropagator(ShipmentRepository shipments, RouteRevisions revisions) {
        this.shipments = Objects.requireNonNull(shipments, "shipments");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
    }

    /**
     * 스캔 하나가 만든 편차를 라우트에 적는다.
     *
     * <p><strong>부르는 쪽의 트랜잭션 안에서</strong>, 그 라우트의 행을 잡은 뒤에 돈다. 스캔의 상태 전이와 편차가 서로 다른
     * 트랜잭션이 되면 「도착했는데 편차는 옛 값인」 창이 생기고, 그 창에 at-risk 판정이 걸리면 이미 해소된 위험을 알리게 된다.
     *
     * @param routeId    라우트 id — <strong>배송이 지금 있는</strong> 라우트다 (ADR-047 결정 1)
     * @param type       스캔 종류
     * @param stopSeq    스캔이 난 stop 순번. 요청이 말한 번호가 아니라 배송의 것이다
     * @param occurredAt 사건 시각 — 기사 단말이 말한 시각이다
     * @return 편차와 남은 배송
     */
    public Propagation propagate(UUID routeId, ScanType type, int stopSeq, Instant occurredAt) {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");

        boolean fromCamp = type == ScanType.DEPARTED_CAMP;
        List<Shipment> onRoute = shipments.findByRouteFrom(routeId, fromCamp ? FIRST_SEQ : stopSeq);
        if (onRoute.isEmpty()) {
            return Propagation.none();
        }

        Duration deviation = Duration.between(baselineOf(routeId, type, stopSeq, onRoute), occurredAt);
        revisions.recordDeviation(routeId, deviation);
        return new Propagation(deviation, remainingOf(onRoute));
    }

    /**
     * 편차의 기준값.
     *
     * @throws NoSuchElementException 스캔이 난 stop 이 목록에 없으면. 부르는 쪽은
     *     {@code (routeId, stopSeq)} 를 <em>요청</em>이 아니라 방금 읽은 배송에서 뽑아 주므로
     *     (ADR-047 결정 1) 여기까지 오면 같은 트랜잭션 안에서 사라졌다는 뜻이고, 그런 경로는
     *     이 서비스에 없다 — 조용히 0 으로 두면 편차가 통째로 사라진다
     */
    private Instant baselineOf(UUID routeId, ScanType type, int stopSeq, List<Shipment> onRoute) {
        if (type == ScanType.DEPARTED_CAMP) {
            return revisions.find(routeId)
                    .orElseThrow(() -> new IllegalStateException(
                            "계획 출발 시각이 없습니다: routeId=" + routeId))
                    .plannedDeparture();
        }
        return onRoute.stream()
                .filter(shipment -> shipment.stopSeq() == stopSeq)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "스캔이 난 stop 이 라우트에 없습니다: routeId=%s seq=%d"
                                .formatted(routeId, stopSeq)))
                .plannedArrival();
    }

    /** 아직 끝나지 않은 배송들. at-risk 판정의 대상이자 페이로드의 「남은 stop」이다. */
    private static List<Shipment> remainingOf(List<Shipment> onRoute) {
        return onRoute.stream().filter(shipment -> !shipment.status().isTerminal()).toList();
    }

    /**
     * 전파 결과.
     *
     * @param deviation 이 스캔이 만든 편차. 음수면 계획보다 이르다
     * @param remaining 아직 끝나지 않은 배송들 (순번 오름차순). at-risk 판정의 대상이다 — ETA 는 planned + {@code deviation}
     */
    public record Propagation(Duration deviation, List<Shipment> remaining) {

        public Propagation {
            Objects.requireNonNull(deviation, "deviation");
            remaining = List.copyOf(remaining);
        }

        /** 이 라우트에 배송이 없다 — 적을 것도 판정할 것도 없다. */
        static Propagation none() {
            return new Propagation(Duration.ZERO, List.of());
        }
    }
}
