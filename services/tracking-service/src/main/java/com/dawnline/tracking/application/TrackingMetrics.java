package com.dawnline.tracking.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.UUID;

/**
 * tracking 고유 메트릭 (DESIGN.md §9.1).
 *
 * <p>카운터를 <strong>기동 때 등록</strong>한다. 늦게 등록하면 그 사건이 한 번도 없는 동안
 * 시계열이 아예 없고, Prometheus 에서 「0 이다」와 「그런 지표가 없다」가 구분되지 않는다 —
 * 전자는 정상이고 후자는 배포 사고다.
 */
public class TrackingMetrics {

    /** §9.1 — 취소된 배송에 도착해 무시한 기사 스캔. 라벨 없음. */
    public static final String SCAN_AFTER_CANCEL = "dawnline.scan.after.cancel";

    /** §9.1 — 기사가 찍은 자리가 지금 아는 자리와 다른 스캔. 라벨 없음 (ADR-047 결정 1). */
    public static final String SCAN_AFTER_RELOCATE = "dawnline.scan.after.relocate";

    /** §9.1 — 발행한 {@code delivery.at-risk}. 라벨 {@code camp}. */
    public static final String AT_RISK = "dawnline.at.risk";

    /** §9.1 — 쿨다운을 쓰지 못해 그냥 발행한 횟수 (Redis 장애, §7.2 fail-open). */
    public static final String COOLDOWN_BYPASSED = "dawnline.at.risk.cooldown.bypassed";

    /** {@code camp} 라벨 이름. 한 곳에서만 적는다 — 라벨 키가 갈리면 등록이 실패한다. */
    private static final String CAMP = "camp";

    private final MeterRegistry registry;
    private final Counter scanAfterCancel;
    private final Counter scanAfterRelocate;
    private final Counter cooldownBypassed;

    /**
     * @param registry 미터 레지스트리
     */
    public TrackingMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.cooldownBypassed = Counter.builder(COOLDOWN_BYPASSED)
                .description("at-risk 쿨다운(Redis)을 쓰지 못해 그냥 발행한 횟수. 오르는 동안 "
                        + "알림이 중복될 수 있다 — 위험을 놓치는 것보다 낫다는 판단이다 (§7.2)")
                .register(registry);
        this.scanAfterCancel = Counter.builder(SCAN_AFTER_CANCEL)
                .description("CANCELLED 인 shipment 에 도착해 무시한 기사 스캔 (DESIGN.md §5.4). "
                        + "dispatch 의 dawnline_cancel_too_late_total 과 한 쌍이다")
                .register(registry);
        this.scanAfterRelocate = Counter.builder(SCAN_AFTER_RELOCATE)
                .description("기사가 찍은 (routeId, stopSeq) 가 지금 tracking 이 아는 자리와 "
                        + "다른 스캔 (DESIGN.md §5.4, ADR-047 결정 1). 무시하지 않고 orderIds 로 "
                        + "풀어 적용한 뒤 센다. dispatch 의 dawnline_status_after_relocate_total "
                        + "과 한 쌍이고, 이쪽이 먼저 오른다")
                .register(registry);
    }

    /**
     * 기사가 옛 개정의 번호로 찍은 스캔을 센다 (§5.4, ADR-047 결정 1).
     *
     * <p><strong>적용한 뒤에</strong> 부른다. 판정에 쓰이지 않는 값이므로 세는 것이 전부이고,
     * 세는 자리가 적재 앞으로 올라가면 롤백된 스캔이 이 숫자에 남는다 — 그리고 그 오해는
     * 「재계획이 돌고 있다」로 읽히므로 가장 나쁜 방향이다.
     *
     * @param count 이번 스캔에서 자리가 어긋난 주문의 수
     */
    public void countScanAfterRelocate(int count) {
        if (count > 0) {
            scanAfterRelocate.increment(count);
        }
    }

    /**
     * 취소 뒤에 온 스캔을 센다 — 무시하되 센다 (§5.4).
     *
     * @param count 이번 스캔에서 그런 주문의 수
     */
    public void countScanAfterCancel(int count) {
        if (count > 0) {
            scanAfterCancel.increment(count);
        }
    }

    /**
     * at-risk 를 발행했다 (§5.4).
     *
     * <p>{@code camp} 라벨이 붙으므로 <strong>기동 때 등록할 수 없다</strong> — 캠프를 미리
     * 알 수 없기 때문이다. 이 미터만 예외이고, 그래서 어떤 캠프의 첫 위험까지는 그 시계열이
     * 없다. 「0 이다」와 「그런 캠프가 없다」의 구별은 캠프 목록을 아는 ops 쪽에서 한다.
     *
     * @param campId 라우트의 캠프 (`route_revisions.camp_id`)
     */
    public void countAtRisk(UUID campId) {
        Objects.requireNonNull(campId, "campId");
        Counter.builder(AT_RISK)
                .description("발행한 delivery.at-risk (DESIGN.md §5.4). 쿨다운이 라우트당 5분이므로 "
                        + "이 값은 「위험한 라우트 수」가 아니라 「알린 횟수」다")
                .tag(CAMP, campId.toString())
                .register(registry)
                .increment();
    }

    /**
     * 쿨다운을 쓰지 못해 그냥 발행했다 (§7.2 fail-open).
     *
     * <p>폴백은 조용히 일어나면 안 된다 — 이 값이 오르는 동안 「알림이 늘었다」는 위험이
     * 늘어난 것이 아니라 Redis 가 죽은 것이다.
     */
    public void countCooldownBypassed() {
        cooldownBypassed.increment();
    }
}
