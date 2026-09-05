package com.dawnline.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.fulfillment.adapter.out.persistence.HaversineFcDistances;
import com.dawnline.fulfillment.adapter.out.redis.GeoIndexLoader;
import com.dawnline.fulfillment.application.FcCandidateAssembler;
import com.dawnline.fulfillment.application.port.out.FcDistances;
import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.FcSelection;
import com.dawnline.fulfillment.domain.FcSelectionResult;
import com.dawnline.fulfillment.domain.FulfillmentCenter;
import com.dawnline.fulfillment.domain.OrderLine;
import com.dawnline.fulfillment.domain.OrderToPlan;
import com.dawnline.fulfillment.domain.ServiceTier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Redis GEO 와 DB 폴백이 <strong>같은 답</strong>을 내는가 (§7.2, 불변규칙 7).
 *
 * <h2>폴백이 지켜야 하는 것은 "동작" 이 아니라 "같은 답" 이다</h2>
 * ADR-020 이 남긴 문장 — <em>멱등 소비자가 막는 것은 중복이지 다른 결과가 아니다</em> — 이 여기에
 * 그대로 적용된다. Redis 가 죽었다는 이유로 같은 주문이 다른 FC 를 받으면, 그것은 폴백이 아니라
 * 조용한 오작동이다.
 *
 * <h2>무엇을 정확히, 무엇을 오차로 보는가</h2>
 * <ul>
 *   <li><strong>순위는 정확히</strong> — 판정이 의존하는 것이 순위이기 때문이다.</li>
 *   <li><strong>거리는 허용 오차로</strong> — Redis 는 좌표를 52비트 geohash 로 양자화하므로
 *       수 m 이 어긋난다. {@code GeoDistance} 가 Redis 와 같은 지구 반지름을 쓰기 때문에 그
 *       양자화 오차만 남는다.</li>
 * </ul>
 *
 * <p>시드 전체(캠프 10 × FC 3)를 돈다. 한 캠프만 보면 순위가 우연히 같을 수 있다.
 */
@SpringBootTest(classes = FulfillmentApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("GeoEquivalenceIT — Redis GEO 와 DB 폴백이 같은 답을 낸다")
class GeoEquivalenceIT extends FulfillmentIntegrationTestBase {

    /** 좌표 양자화(약 0.6 m)를 고려한 여유. 이보다 크게 벌어지면 계산식이 다른 것이다. */
    private static final double DISTANCE_TOLERANCE_KM = 0.01;

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    @Autowired
    private ReferenceData referenceData;

    @Autowired
    private FcDistances redisDistances;

    @Autowired
    private GeoIndexLoader loader;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;

    private FcDistances fallbackDistances;

    private static boolean loaded;

    @BeforeAll
    static void resetFlag() {
        loaded = false;
    }

    private void ensureLoaded() {
        fallbackDistances = new HaversineFcDistances(referenceData);
        if (!loaded) {
            assertThat(loader.loadCenters()).as("geo:fc 적재").isTrue();
            assertThat(loader.loadCamps()).as("geo:camp 적재").isTrue();
            loaded = true;
        }
    }

    @Test
    void 모든_캠프에서_FC_순위가_정확히_같다() {
        ensureLoaded();
        List<UUID> fcIds = fcIds();
        List<String> mismatches = new ArrayList<>();

        for (Camp camp : referenceData.findAllCamps()) {
            List<UUID> viaRedis = ranked(redisDistances.fromCamp(camp, fcIds));
            List<UUID> viaFallback = ranked(fallbackDistances.fromCamp(camp, fcIds));
            if (!viaRedis.equals(viaFallback)) {
                mismatches.add("%s: redis=%s fallback=%s".formatted(camp.code(), viaRedis, viaFallback));
            }
        }

        assertThat(mismatches)
                .as("Redis 가 죽었다는 이유로 다른 FC 가 선택되면 폴백이 아니라 조용한 오작동이다")
                .isEmpty();
    }

    @Test
    void 거리는_양자화_오차_안에서_같다() {
        ensureLoaded();
        List<UUID> fcIds = fcIds();

        for (Camp camp : referenceData.findAllCamps()) {
            Map<UUID, Double> viaRedis = redisDistances.fromCamp(camp, fcIds);
            Map<UUID, Double> viaFallback = fallbackDistances.fromCamp(camp, fcIds);

            assertThat(viaRedis).as("%s — Redis 가 모든 FC 를 채워야 한다", camp.code())
                    .containsOnlyKeys(fcIds.toArray(UUID[]::new));
            viaFallback.forEach((id, km) -> assertThat(viaRedis.get(id))
                    .as("%s → %s", camp.code(), id)
                    .isCloseTo(km, org.assertj.core.data.Offset.offset(DISTANCE_TOLERANCE_KM)));
        }
    }

    @Test
    void 모든_캠프_티어_냉장_조합에서_판정_결과가_같다() {
        // 순위가 같아도 판정이 같은지는 별개다 — 반경 경계에 걸린 FC 가 있으면 갈릴 수 있다.
        // 시드 전체 60조합(캠프 10 × 티어 3 × 냉장 2)을 돈다.
        ensureLoaded();
        FcSelection selection = new FcSelection(Clock.fixed(NOW, ZoneOffset.UTC), java.time.Duration.ofHours(24));
        FcCandidateAssembler viaRedis = new FcCandidateAssembler(referenceData, redisDistances);
        FcCandidateAssembler viaFallback = new FcCandidateAssembler(referenceData, fallbackDistances);
        List<String> mismatches = new ArrayList<>();

        for (Camp camp : referenceData.findAllCamps()) {
            for (ServiceTier tier : ServiceTier.values()) {
                for (boolean cold : List.of(false, true)) {
                    OrderToPlan order = order(tier, cold);
                    FcSelectionResult a = selection.select(order, camp, viaRedis.forCamp(camp, order.lines()));
                    FcSelectionResult b = selection.select(order, camp, viaFallback.forCamp(camp, order.lines()));
                    if (!describe(a).equals(describe(b))) {
                        mismatches.add("%s/%s/cold=%s: redis=%s fallback=%s"
                                .formatted(camp.code(), tier, cold, describe(a), describe(b)));
                    }
                }
            }
        }

        assertThat(mismatches).isEmpty();
    }

    @Test
    void 적재하지_않은_인덱스는_폴백으로_간다() {
        // geo:fc 를 비우면 GEOSEARCH 가 빈 결과를 준다. 부분 결과(0건)는 폴백보다 나쁘므로
        // 어댑터가 폴백으로 넘겨야 하고, 답은 그대로여야 한다.
        ensureLoaded();
        List<UUID> fcIds = fcIds();
        Camp camp = referenceData.findAllCamps().getFirst();
        Map<UUID, Double> expected = fallbackDistances.fromCamp(camp, fcIds);

        deleteGeoKey();
        Map<UUID, Double> afterDelete = redisDistances.fromCamp(camp, fcIds);

        assertThat(afterDelete).containsOnlyKeys(fcIds.toArray(UUID[]::new));
        expected.forEach((id, km) -> assertThat(afterDelete.get(id))
                .isCloseTo(km, org.assertj.core.data.Offset.offset(1e-9)));

        assertThat(loader.loadCenters()).as("다시 적재하면 복구된다 — GEOADD 는 멱등이다").isTrue();
        loaded = true;
    }

    private void deleteGeoKey() {
        redis.delete(com.dawnline.fulfillment.adapter.out.redis.RedisFcDistances.FC_KEY);
    }

    private static String describe(FcSelectionResult result) {
        return switch (result) {
            case FcSelectionResult.Selected selected ->
                    "SELECTED:" + selected.fc().code() + ":" + selected.fallbackReason();
            case FcSelectionResult.Unserviceable unserviceable ->
                    "UNSERVICEABLE:" + unserviceable.reason();
        };
    }

    private static OrderToPlan order(ServiceTier tier, boolean cold) {
        return new OrderToPlan(UUID.randomUUID(), tier, cold,
                List.of(new OrderLine("SKU-00001", 1)), NOW.plusSeconds(3600));
    }

    private List<UUID> fcIds() {
        return referenceData.findAllCenters().stream().map(FulfillmentCenter::id).toList();
    }

    /** 거리 오름차순, 동률이면 id 순 — 판정의 동률 규칙(FC 코드)과 별개로 순위 자체를 비교한다. */
    private static List<UUID> ranked(Map<UUID, Double> distances) {
        return distances.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();
    }
}
