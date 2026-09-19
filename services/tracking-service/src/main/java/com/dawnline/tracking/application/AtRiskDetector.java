package com.dawnline.tracking.application;

import com.dawnline.tracking.application.EtaPropagator.Propagation;
import com.dawnline.tracking.application.port.out.AtRiskCooldown;
import com.dawnline.tracking.application.port.out.DeliveryEvents;
import com.dawnline.tracking.application.port.out.RouteRevisions;
import com.dawnline.tracking.domain.Shipment;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 지연 위험 판정과 통지 (DESIGN.md §5.4 — {@code eta > promised_end − 15분}).
 *
 * <h2>사건이지 상태가 아니다</h2>
 * 위험이 계속되면 다시 알린다 — 쿨다운이 그 주기다. 위험이 <strong>사라지는</strong> 경우는
 * 알리지 않는다: 이미 시작된 재계획을 취소할 방법이 없고, 해소된 ETA 는 ops 의 읽기 모델이
 * 그대로 보여 준다. 그 비대칭이 의도이고 [ADR-046](docs/adr/ADR-046-at-risk-is-an-event.md) 이
 * 그것을 적어 둔다.
 *
 * <h2>쿨다운이 지키는 것은 알림 수다</h2>
 * 재계획이 두 번 도는 것을 막는 장치가 <em>아니다</em>. Redis 가 죽으면 중복 발행되고 그것은
 * §7.2 가 허용으로 정한 폴백이다. 중복이 재계획 두 번이 되지 않게 하는 것은 dispatch 의 DB
 * 쿨다운이고(§6.8, {@code routes.last_replanned_at}), 멱등 소비자는 {@code eventId} 가 다르므로
 * 막지 못한다. <strong>5-3 에서 「이미 있으니 됐다」가 나오지 않게 하려고</strong> 이 문단이 있다.
 *
 * <h2>판정과 발행 사이에 트랜잭션 경계를 두지 않는다</h2>
 * 이 클래스는 스캔 유스케이스의 트랜잭션 안에서 돈다. 발행은 outbox INSERT 하나라 스캔이
 * 롤백되면 알림도 사라진다 — 「상태는 되돌아갔는데 위험 알림은 나간」 라우트가 생기지 않는다.
 */
public class AtRiskDetector {

    private static final Logger log = LoggerFactory.getLogger(AtRiskDetector.class);

    private final RouteRevisions revisions;
    private final AtRiskCooldown cooldown;
    private final DeliveryEvents delivery;
    private final TrackingMetrics metrics;
    private final Clock clock;
    private final Duration margin;

    /**
     * @param revisions 라우트당 계획값 — {@code campId} 의 출처다
     * @param cooldown  라우트당 알림 쿨다운 (§7.2)
     * @param delivery  발행 포트 (outbox)
     * @param metrics   §9.1 카운터
     * @param clock     판정 시각 (불변규칙 12)
     * @param margin    약속 끝에서 앞당겨 보는 여유 (§5.4 기본 15분)
     */
    public AtRiskDetector(RouteRevisions revisions, AtRiskCooldown cooldown,
            DeliveryEvents delivery, TrackingMetrics metrics, Clock clock, Duration margin) {
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.margin = Objects.requireNonNull(margin, "margin");
    }

    /**
     * 전파 결과를 보고 필요하면 알린다.
     *
     * @param routeId     라우트 id
     * @param propagation 방금 전파한 결과
     * @return 발행했으면 {@code true}
     */
    public boolean evaluate(UUID routeId, Propagation propagation) {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(propagation, "propagation");

        List<Shipment> remaining = propagation.remaining();
        if (remaining.stream().noneMatch(shipment -> shipment.isAtRisk(margin))) {
            // 위험이 없거나, 방금 사라졌다. 어느 쪽이든 이벤트는 나가지 않는다 — 위 비대칭.
            return false;
        }
        if (!cooldown.tryStart(routeId)) {
            // 최근에 알렸다. 같은 사실을 stop 마다 반복하지 않는다.
            return false;
        }
        UUID campId = revisions.find(routeId)
                .orElseThrow(() -> new IllegalStateException(
                        "계획값 없는 라우트의 위험을 알릴 수 없습니다: routeId=" + routeId))
                .campId();

        delivery.deliveryAtRisk(routeId, campId, clock.instant(), propagation.deviation(),
                remaining, margin);
        metrics.countAtRisk(campId);
        log.info("지연 위험을 알렸다. routeId={}, remaining={}, deviationS={}",
                routeId, remaining.size(), propagation.deviation().toSeconds());
        return true;
    }
}
