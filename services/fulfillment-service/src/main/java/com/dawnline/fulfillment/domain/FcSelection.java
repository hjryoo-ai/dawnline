package com.dawnline.fulfillment.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * FC 선택 (DESIGN.md §5.2 1~6단계) — <strong>순수 함수</strong>.
 *
 * <h2>Spring 도 Redis 도 DB 도 모른다</h2>
 * 어댑터가 후보 FC 목록과 <em>캠프까지의 거리·재고</em>를 준비해 넘기고, 판정만 여기서 한다
 * (불변규칙 5). 그래서 이 클래스는 컨테이너 없이 단위 테스트된다. 시드에 일부러 넣은 세 결손
 * ({@code tier}/{@code cold}/{@code inventory})이 각각 fallback 사유로 나오는 테스트가
 * <strong>이 함수의 명세</strong>다.
 *
 * <h2>판정 순서</h2>
 * <ol>
 *   <li><strong>{@code STALE_PLACED}</strong> — {@code cutoffAt < now − 24h} 면 여기서 끝낸다
 *       (ADR-020 후속 정정). FC 선택 <em>전</em>에 보는 이유는, 컷오프가 하루를 넘긴 주문은
 *       FC·재고를 볼 이유가 없기 때문이다.</li>
 *   <li>{@code NO_ACTIVE_CAMP} — 캠프가 비활성.</li>
 *   <li>1~3단계 필터(티어 → 냉장 → 재고)를 <strong>전체 FC</strong>에 적용해 적격 집합을 만든다.
 *       중간에 비면 <em>그 단계의 사유</em>로 끝낸다. 순서가 곧 사유의 우선순위다.</li>
 *   <li>홈 FC 가 적격 집합에 있으면 그대로 쓴다(fallback 없음).</li>
 *   <li>없으면 <strong>캠프 반경 50 km 안</strong>의 적격 FC 중 가장 가까운 것을 고르고,
 *       홈 FC 가 떨어진 필터를 fallback 사유로 남긴다 (ADR-021 결정 3).</li>
 *   <li>반경 안에 적격 FC 가 없으면 {@code NO_ELIGIBLE_FC}.</li>
 * </ol>
 *
 * <h2>반경은 홈 FC 에 적용하지 않는다</h2>
 * §5.2 5단계의 {@code BYRADIUS 50 km} 는 <em>대체</em> FC 를 고르는 단계의 상한이다. 홈 FC 는
 * 캠프에 배정된 기본값이므로 거리와 무관하게 쓴다 — 홈 FC 가 50 km 밖이라면 그것은 이 판정이
 * 고칠 문제가 아니라 캠프-FC 배정의 문제다.
 *
 * <h2>{@code NO_ZONE_MATCH} 는 여기서 나오지 않는다</h2>
 * 그 판정은 권역 조회를 하는 쪽(어댑터·유스케이스)이 내린다. 권역을 못 찾으면 캠프가 없고,
 * 캠프가 없으면 이 함수를 부를 수 없다.
 */
public final class FcSelection {

    /** §5.2 5단계 — FC → 캠프 간선(linehaul)의 상한. */
    public static final double LINEHAUL_RADIUS_KM = 50.0;

    /** ADR-020 후속 정정의 기본 상한. */
    public static final Duration DEFAULT_STALE_PLACED_AFTER = Duration.ofHours(24);

    private final Clock clock;
    private final Duration stalePlacedAfter;

    /**
     * @param clock            시각 출처 (불변규칙 12)
     * @param stalePlacedAfter 이 시간을 넘긴 컷오프는 {@code STALE_PLACED}
     */
    public FcSelection(Clock clock, Duration stalePlacedAfter) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stalePlacedAfter = Objects.requireNonNull(stalePlacedAfter, "stalePlacedAfter");
        if (stalePlacedAfter.isNegative() || stalePlacedAfter.isZero()) {
            throw new IllegalArgumentException("stalePlacedAfter 는 양수여야 합니다: " + stalePlacedAfter);
        }
    }

    /**
     * 기본 상한(24시간)으로 만든다.
     *
     * @param clock 시각 출처
     */
    public static FcSelection withDefaults(Clock clock) {
        return new FcSelection(clock, DEFAULT_STALE_PLACED_AFTER);
    }

    /**
     * FC 를 고른다.
     *
     * @param order      주문
     * @param camp       주소 권역이 가리키는 캠프
     * @param candidates 후보 FC 전체. 어댑터가 캠프까지의 거리와 재고를 채워 넘긴다
     */
    public FcSelectionResult select(OrderToPlan order, Camp camp, List<CandidateFc> candidates) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(camp, "camp");
        Objects.requireNonNull(candidates, "candidates");

        if (isStale(order.cutoffAt())) {
            return new FcSelectionResult.Unserviceable(UnserviceableReason.STALE_PLACED);
        }
        if (!camp.active()) {
            return new FcSelectionResult.Unserviceable(UnserviceableReason.NO_ACTIVE_CAMP);
        }

        List<CandidateFc> live = candidates.stream().filter(CandidateFc::active).toList();

        // 1~3단계. 각 단계에서 집합이 비면 그 단계의 사유가 답이다 — 순서가 우선순위다.
        List<CandidateFc> byTier = live.stream().filter(fc -> fc.supports(order.serviceTier())).toList();
        if (byTier.isEmpty()) {
            return new FcSelectionResult.Unserviceable(UnserviceableReason.NO_FC_FOR_TIER);
        }
        List<CandidateFc> byCold = byTier.stream().filter(coldFilter(order)).toList();
        if (byCold.isEmpty()) {
            return new FcSelectionResult.Unserviceable(UnserviceableReason.NO_COLD_FC);
        }
        List<CandidateFc> eligible = byCold.stream().filter(fc -> fc.hasStockFor(order.lines())).toList();
        if (eligible.isEmpty()) {
            return new FcSelectionResult.Unserviceable(UnserviceableReason.OUT_OF_STOCK);
        }

        // 4단계 — 홈 FC 가 살아남았으면 그대로 쓴다. 거리는 보지 않는다.
        Optional<CandidateFc> home = eligible.stream()
                .filter(fc -> fc.id().equals(camp.homeFcId()))
                .findFirst();
        if (home.isPresent()) {
            return new FcSelectionResult.Selected(home.get(), null);
        }

        // 5단계 — 대체 FC. 여기서만 반경이 걸린다.
        Optional<CandidateFc> nearest = eligible.stream()
                .filter(fc -> fc.distanceFromCampKm() <= LINEHAUL_RADIUS_KM)
                .min(Comparator.comparingDouble(CandidateFc::distanceFromCampKm));
        if (nearest.isEmpty()) {
            return new FcSelectionResult.Unserviceable(UnserviceableReason.NO_ELIGIBLE_FC);
        }
        return new FcSelectionResult.Selected(nearest.get(), fallbackReason(order, camp, live));
    }

    /**
     * 홈 FC 가 떨어진 필터. 1~3단계 순서대로 처음 걸리는 것을 답으로 한다.
     *
     * <p>홈 FC 가 후보 목록에 아예 없거나 비활성이면 {@link FcFallbackReason#TIER} 로 본다 —
     * "그 티어를 처리할 수 없다" 의 가장 넓은 경우이고, 실제로는 캠프-FC 배정이 깨진 상황이라
     * 어느 사유로 세든 그 캠프의 카운터가 오르는 것이 목적이다.
     */
    private FcFallbackReason fallbackReason(OrderToPlan order, Camp camp, List<CandidateFc> live) {
        Optional<CandidateFc> home = live.stream()
                .filter(fc -> fc.id().equals(camp.homeFcId()))
                .findFirst();
        if (home.isEmpty() || !home.get().supports(order.serviceTier())) {
            return FcFallbackReason.TIER;
        }
        if (order.requiresCold() && !home.get().supportsCold()) {
            return FcFallbackReason.COLD;
        }
        return FcFallbackReason.INVENTORY;
    }

    private static Predicate<CandidateFc> coldFilter(OrderToPlan order) {
        return fc -> !order.requiresCold() || fc.supportsCold();
    }

    /**
     * 컷오프가 상한을 넘겼는가 (ADR-020 후속 정정).
     *
     * <p>경계는 <strong>포함하지 않는다</strong> — 정확히 24시간 지난 컷오프는 아직 유효하다.
     * 경계 양쪽 1초로 테스트한다.
     *
     * @param cutoffAt 컷오프 시각
     */
    public boolean isStale(Instant cutoffAt) {
        return cutoffAt.isBefore(clock.instant().minus(stalePlacedAfter));
    }
}
