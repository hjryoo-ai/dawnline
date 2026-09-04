package com.dawnline.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * FC 선택의 <strong>명세</strong> (DESIGN.md §5.2 1~6단계, ADR-020 후속 정정, ADR-021).
 *
 * <p>후보는 {@code R__seed_fulfillment.sql} 의 세 FC 를 그대로 옮겼다. 시드에 일부러 넣은
 * 세 결손이 각각 fallback 사유로 나오는 것이 이 함수가 지켜야 할 계약이다.
 *
 * <ul>
 *   <li>{@code FC-GOYANG} — {@code DAWN} 미지원 → {@link FcFallbackReason#TIER}</li>
 *   <li>{@code FC-HWASEONG} — 냉장 미지원 → {@link FcFallbackReason#COLD}</li>
 *   <li>{@code SKU-01337} — 화성만 품절 → {@link FcFallbackReason#INVENTORY}</li>
 * </ul>
 *
 * <p>Spring 도 Redis 도 DB 도 없다. 거리와 재고는 어댑터가 준비해 넘기는 값이므로 여기서는
 * 그냥 만들어 넣는다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FcSelectionTest {

    private static final Instant NOW = Instant.parse("2026-09-05T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Instant FRESH_CUTOFF = NOW.minus(Duration.ofMinutes(5));

    private static final UUID GOYANG = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SEOUL = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID HWASEONG = UUID.fromString("00000000-0000-4000-8000-000000000003");

    private static final Set<ServiceTier> ALL_TIERS = Set.of(ServiceTier.values());
    private static final Set<ServiceTier> NO_DAWN = Set.of(ServiceTier.SAME_DAY, ServiceTier.NEXT_DAY);

    private final FcSelection selection = FcSelection.withDefaults(CLOCK);

    // --- 픽스처 -------------------------------------------------------------

    private static CandidateFc fc(UUID id, String code, Set<ServiceTier> tiers, boolean cold,
            double distanceKm, Map<String, Integer> stock) {
        return new CandidateFc(id, code, tiers, cold, true, distanceKm, stock);
    }

    /** 시드의 세 FC. 거리는 캠프에 따라 바뀌므로 호출부가 넣는다. */
    private static List<CandidateFc> seedFcs(double goyangKm, double seoulKm, double hwaseongKm,
            Map<String, Integer> hwaseongStock) {
        return List.of(
                fc(GOYANG, "FC-GOYANG", NO_DAWN, true, goyangKm, Map.of()),
                fc(SEOUL, "FC-SEOUL", ALL_TIERS, true, seoulKm, Map.of()),
                fc(HWASEONG, "FC-HWASEONG", ALL_TIERS, false, hwaseongKm, hwaseongStock));
    }

    private static Camp camp(UUID homeFcId) {
        return new Camp(UUID.randomUUID(), "CAMP-TEST", homeFcId, GeoPoint.of(37.5, 127.0), true);
    }

    private static OrderToPlan order(ServiceTier tier, boolean cold, String sku, int qty) {
        return new OrderToPlan(UUID.randomUUID(), tier, cold, List.of(new OrderLine(sku, qty)), FRESH_CUTOFF);
    }

    private static OrderToPlan order(ServiceTier tier, boolean cold) {
        return order(tier, cold, "SKU-00001", 1);
    }

    private static FcSelectionResult.Selected selected(FcSelectionResult result) {
        assertThat(result).isInstanceOf(FcSelectionResult.Selected.class);
        return (FcSelectionResult.Selected) result;
    }

    // --- 홈 FC 를 그대로 쓰는 경우 --------------------------------------------

    @Test
    void 홈_FC_가_필터를_통과하면_그대로_쓴다() {
        FcSelectionResult result = selection.select(
                order(ServiceTier.SAME_DAY, false), camp(SEOUL), seedFcs(20, 5, 30, Map.of()));

        FcSelectionResult.Selected picked = selected(result);
        assertThat(picked.fc().id()).isEqualTo(SEOUL);
        assertThat(picked.isFallback()).isFalse();
        assertThat(picked.fallbackReason()).isNull();
    }

    @Test
    void 홈_FC_는_반경_50km_밖이어도_쓴다() {
        // §5.2 5단계의 BYRADIUS 는 <대체> FC 를 고르는 단계의 상한이다. 홈 FC 가 멀다면
        // 그것은 이 판정이 고칠 문제가 아니라 캠프-FC 배정의 문제다 (ADR-021).
        FcSelectionResult result = selection.select(
                order(ServiceTier.SAME_DAY, false), camp(SEOUL), seedFcs(20, 120, 30, Map.of()));

        assertThat(selected(result).fc().id()).isEqualTo(SEOUL);
    }

    // --- 시드의 세 결손이 각각 fallback 사유로 나온다 (이 함수의 명세) ------------

    @Test
    void 홈_FC_가_티어를_지원하지_않으면_TIER_로_대체한다() {
        // FC-GOYANG 은 DAWN 미지원. 경기 북부·서부 캠프의 새벽 주문이 이 경로를 탄다.
        FcSelectionResult result = selection.select(
                order(ServiceTier.DAWN, false), camp(GOYANG), seedFcs(5, 25, 50, Map.of()));

        FcSelectionResult.Selected picked = selected(result);
        assertThat(picked.fc().id()).isEqualTo(SEOUL);
        assertThat(picked.fallbackReason()).isEqualTo(FcFallbackReason.TIER);
    }

    @Test
    void 홈_FC_가_냉장을_지원하지_않으면_COLD_로_대체한다() {
        // FC-HWASEONG 은 냉장 미지원. 경기 남부 캠프의 냉장 주문이 이 경로를 탄다.
        FcSelectionResult result = selection.select(
                order(ServiceTier.SAME_DAY, true), camp(HWASEONG), seedFcs(37, 27, 3, Map.of()));

        FcSelectionResult.Selected picked = selected(result);
        assertThat(picked.fc().id()).isEqualTo(SEOUL);
        assertThat(picked.fallbackReason()).isEqualTo(FcFallbackReason.COLD);
    }

    @Test
    void 홈_FC_만_품절이면_INVENTORY_로_대체한다() {
        // SKU-01337 은 화성에서만 0 이다. 다른 FC 에는 있으므로 OUT_OF_STOCK 이 아니라 대체다.
        FcSelectionResult result = selection.select(
                order(ServiceTier.SAME_DAY, false, "SKU-01337", 1),
                camp(HWASEONG),
                seedFcs(37, 27, 3, Map.of("SKU-01337", 0)));

        FcSelectionResult.Selected picked = selected(result);
        assertThat(picked.fc().id()).isEqualTo(SEOUL);
        assertThat(picked.fallbackReason()).isEqualTo(FcFallbackReason.INVENTORY);
    }

    @Test
    void 대체_FC_는_캠프에서_가장_가까운_것이다() {
        // 거리 기준점이 고객 주소가 아니라 캠프다 — 라스트마일은 어느 FC 를 쓰든 캠프에서
        // 출발하므로 달라지는 비용은 FC → 캠프 간선뿐이다 (ADR-021 결정 3-a).
        FcSelectionResult result = selection.select(
                order(ServiceTier.DAWN, false), camp(GOYANG), seedFcs(5, 40, 12, Map.of()));

        assertThat(selected(result).fc().id()).isEqualTo(HWASEONG);
    }

    // --- UNSERVICEABLE 사유 --------------------------------------------------

    @Test
    void 어느_FC_도_티어를_지원하지_않으면_NO_FC_FOR_TIER_다() {
        List<CandidateFc> onlyGoyang = List.of(fc(GOYANG, "FC-GOYANG", NO_DAWN, true, 5, Map.of()));

        FcSelectionResult result = selection.select(order(ServiceTier.DAWN, false), camp(GOYANG), onlyGoyang);

        assertThat(result.unserviceableReason()).contains(UnserviceableReason.NO_FC_FOR_TIER);
    }

    @Test
    void 냉장이_필요한데_냉장_FC_가_없으면_NO_COLD_FC_다() {
        List<CandidateFc> onlyHwaseong =
                List.of(fc(HWASEONG, "FC-HWASEONG", ALL_TIERS, false, 3, Map.of()));

        FcSelectionResult result =
                selection.select(order(ServiceTier.SAME_DAY, true), camp(HWASEONG), onlyHwaseong);

        assertThat(result.unserviceableReason()).contains(UnserviceableReason.NO_COLD_FC);
    }

    @Test
    void 모든_FC_가_품절이면_OUT_OF_STOCK_이다() {
        // SKU-00013 은 시드에서 전 FC 0 이다.
        Map<String, Integer> soldOut = Map.of("SKU-00013", 0);
        List<CandidateFc> all = List.of(
                fc(GOYANG, "FC-GOYANG", NO_DAWN, true, 5, soldOut),
                fc(SEOUL, "FC-SEOUL", ALL_TIERS, true, 25, soldOut),
                fc(HWASEONG, "FC-HWASEONG", ALL_TIERS, false, 50, soldOut));

        FcSelectionResult result = selection.select(
                order(ServiceTier.SAME_DAY, false, "SKU-00013", 1), camp(SEOUL), all);

        assertThat(result.unserviceableReason()).contains(UnserviceableReason.OUT_OF_STOCK);
    }

    @Test
    void 수량이_모자라도_품절이다() {
        // SKU-00666 은 전 FC 1개다. 2개를 주문하면 모자란다.
        Map<String, Integer> one = Map.of("SKU-00666", 1);
        List<CandidateFc> all = List.of(
                fc(SEOUL, "FC-SEOUL", ALL_TIERS, true, 25, one),
                fc(HWASEONG, "FC-HWASEONG", ALL_TIERS, false, 50, one));

        assertThat(selection.select(order(ServiceTier.SAME_DAY, false, "SKU-00666", 2), camp(SEOUL), all)
                .unserviceableReason()).contains(UnserviceableReason.OUT_OF_STOCK);
        assertThat(selection.select(order(ServiceTier.SAME_DAY, false, "SKU-00666", 1), camp(SEOUL), all)
                .selectedFc()).isPresent();
    }

    @Test
    void 재고_표에_없는_SKU_는_가용이다() {
        // 스텁의 규칙 — 행이 없으면 가용. 2,000개 SKU 를 전부 적지 않기 위한 선택이다.
        FcSelectionResult result = selection.select(
                order(ServiceTier.SAME_DAY, false, "SKU-99999", 999),
                camp(SEOUL), seedFcs(20, 5, 30, Map.of()));

        assertThat(selected(result).fc().id()).isEqualTo(SEOUL);
    }

    @Test
    void 캠프가_비활성이면_NO_ACTIVE_CAMP_다() {
        Camp inactive = new Camp(UUID.randomUUID(), "CAMP-OFF", SEOUL, GeoPoint.of(37.5, 127.0), false);

        FcSelectionResult result =
                selection.select(order(ServiceTier.SAME_DAY, false), inactive, seedFcs(20, 5, 30, Map.of()));

        assertThat(result.unserviceableReason()).contains(UnserviceableReason.NO_ACTIVE_CAMP);
    }

    @Test
    void 적격_FC_가_전부_반경_밖이면_NO_ELIGIBLE_FC_다() {
        // 홈(GOYANG)이 DAWN 을 못 하고, DAWN 이 되는 둘은 50 km 밖이다.
        FcSelectionResult result = selection.select(
                order(ServiceTier.DAWN, false), camp(GOYANG), seedFcs(5, 51, 60, Map.of()));

        assertThat(result.unserviceableReason()).contains(UnserviceableReason.NO_ELIGIBLE_FC);
    }

    @Test
    void 비활성_FC_는_후보에서_빠진다() {
        List<CandidateFc> withInactive = List.of(
                new CandidateFc(SEOUL, "FC-SEOUL", ALL_TIERS, true, false, 5, Map.of()),
                fc(HWASEONG, "FC-HWASEONG", ALL_TIERS, false, 20, Map.of()));

        FcSelectionResult result =
                selection.select(order(ServiceTier.DAWN, false), camp(SEOUL), withInactive);

        assertThat(selected(result).fc().id()).isEqualTo(HWASEONG);
        assertThat(selected(result).fallbackReason()).isEqualTo(FcFallbackReason.TIER);
    }

    // --- STALE_PLACED 경계 (ADR-020 후속 정정) --------------------------------

    @Test
    void 컷오프가_24시간을_넘기면_STALE_PLACED_다() {
        OrderToPlan stale = new OrderToPlan(UUID.randomUUID(), ServiceTier.SAME_DAY, false,
                List.of(new OrderLine("SKU-00001", 1)),
                NOW.minus(Duration.ofHours(24)).minusSeconds(1));

        FcSelectionResult result = selection.select(stale, camp(SEOUL), seedFcs(20, 5, 30, Map.of()));

        assertThat(result.unserviceableReason()).contains(UnserviceableReason.STALE_PLACED);
    }

    @Test
    void 경계_1초_전은_아직_유효하다() {
        OrderToPlan justInside = new OrderToPlan(UUID.randomUUID(), ServiceTier.SAME_DAY, false,
                List.of(new OrderLine("SKU-00001", 1)),
                NOW.minus(Duration.ofHours(24)).plusSeconds(1));

        FcSelectionResult result = selection.select(justInside, camp(SEOUL), seedFcs(20, 5, 30, Map.of()));

        assertThat(selected(result).fc().id()).isEqualTo(SEOUL);
    }

    @Test
    void 정확히_24시간_지난_컷오프는_아직_유효하다() {
        // 경계를 포함하지 않는다. 양쪽 1초로 확인했으므로 이 값이 어느 쪽인지도 못 박아 둔다.
        assertThat(selection.isStale(NOW.minus(Duration.ofHours(24)))).isFalse();
        assertThat(selection.isStale(NOW.minus(Duration.ofHours(24)).minusSeconds(1))).isTrue();
        assertThat(selection.isStale(NOW.minus(Duration.ofHours(24)).plusSeconds(1))).isFalse();
    }

    @Test
    void STALE_PLACED_는_FC_선택보다_먼저_판정한다() {
        // 컷오프가 하루를 넘긴 주문은 FC·재고를 볼 이유가 없다. 후보가 아예 없어도
        // NO_FC_FOR_TIER 가 아니라 STALE_PLACED 가 나와야 한다.
        OrderToPlan stale = new OrderToPlan(UUID.randomUUID(), ServiceTier.DAWN, true,
                List.of(new OrderLine("SKU-00013", 99)),
                NOW.minus(Duration.ofDays(20)));

        FcSelectionResult result = selection.select(stale, camp(SEOUL), List.of());

        assertThat(result.unserviceableReason()).contains(UnserviceableReason.STALE_PLACED);
    }

    @Test
    void 상한은_설정값이다() {
        FcSelection oneHour = new FcSelection(CLOCK, Duration.ofHours(1));

        assertThat(oneHour.isStale(NOW.minus(Duration.ofMinutes(61)))).isTrue();
        assertThat(oneHour.isStale(NOW.minus(Duration.ofMinutes(59)))).isFalse();
    }
}
