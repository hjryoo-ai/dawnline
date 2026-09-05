package com.dawnline.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.fulfillment.adapter.out.redis.GeoIndexLoader;
import com.dawnline.fulfillment.adapter.out.redis.GeoMetrics;
import com.dawnline.fulfillment.application.FcCandidateAssembler;
import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.CandidateFc;
import com.dawnline.fulfillment.domain.FcSelection;
import com.dawnline.fulfillment.domain.FcSelectionResult;
import com.dawnline.fulfillment.domain.OrderLine;
import com.dawnline.fulfillment.domain.OrderToPlan;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.Zone;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * FC 선택이 <strong>Redis 없이도</strong> 성립한다 (§7.2, 불변규칙 7, ADR-016 후속 정정).
 *
 * <p>Redis 를 일부러 죽은 주소로 가리킨다. 그래서 이 클래스가 통과한다는 것은 GEO 인덱스도
 * 권역 캐시도 통째로 없을 때 <strong>DB 만으로 같은 판정이 나온다</strong>는 증명이다 —
 * {@code PlaceOrderIT} 가 order-service 의 멱등에 대해 하는 일을 여기서는 FC 선택에 대해 한다.
 *
 * <p>Redis 를 켜 둔 개발 기계에서도 결과가 같도록 호스트를 고정한다. 로컬에 Redis 가 떠 있는지에
 * 따라 검사 대상이 달라지는 테스트는 아무것도 증명하지 못한다.
 *
 * <h2>기동이 막히지 않는다는 것도 이 클래스가 증명한다</h2>
 * 컨텍스트가 뜬다는 사실 자체가 "GEO 적재 실패는 기동을 막지 않는다" 이다. 이전 §8.6 은 적재
 * 완료를 레디니스 조건으로 적고 있었고, 그것이 폴백을 만든 이유를 지우는 모순이었다.
 */
@SpringBootTest(classes = FulfillmentApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("GeoFallbackIT — Redis 없이도 성립하는 FC 선택")
class GeoFallbackIT extends FulfillmentIntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    @Autowired
    private ReferenceData referenceData;

    @Autowired
    private FcCandidateAssembler assembler;

    @Autowired
    private FcSelection selection;

    @Autowired
    private GeoIndexLoader loader;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * 죽은 Redis 를 가리킨다.
     *
     * <p><strong>{@code host}/{@code port} 가 아니라 {@code url} 로 덮는다.</strong> 기반 클래스도
     * {@code @DynamicPropertySource} 로 살아 있는 컨테이너 주소를 등록하는데, 두 메서드의 적용
     * 순서는 보장되지 않는다 — 실제로 host/port 로 덮었더니 기반 클래스가 이겨서 이 테스트가
     * <em>살아 있는 Redis</em> 를 보고 통과했다. {@code spring.data.redis.url} 은 host/port 보다
     * 우선하므로 순서와 무관하게 이긴다.
     */
    @DynamicPropertySource
    static void deadRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", () -> "redis://127.0.0.1:1");
    }

    /**
     * <strong>이 클래스의 전제를 매번 스스로 확인한다: Redis 를 쓸 수 없다.</strong>
     *
     * <p>폴백 테스트는 의존성이 실제로 불가할 때만 무언가를 증명한다. 전제가 조용히 무너지면
     * 테스트는 <em>계속 통과하면서</em> 아무것도 검사하지 않는 상태가 된다 — 실제로 이 클래스가
     * 그랬다. 기반 클래스도 {@code @DynamicPropertySource} 로 살아 있는 컨테이너 주소를 등록하는데
     * 두 메서드의 적용 순서가 보장되지 않아, {@code host}/{@code port} 로 덮은 첫 판이 살아 있는
     * Redis 를 보고 통과했다.
     *
     * <p>그래서 주소를 {@code spring.data.redis.url}(host/port 보다 우선)로 덮는 것과 별개로,
     * <em>덮였다는 사실 자체</em>를 어설션으로 둔다. 이것이 이 프로젝트의 세 번째 사례이고
     * (PlaceOrderIT 의 주소 고정, OrderApiIT 의 {@code tryLock UNAVAILABLE} 확인), 그래서 규칙이
     * 되었다 — CLAUDE.md 「폴백 테스트는 전제를 첫 어설션으로 스스로 말한다」.
     */
    @BeforeEach
    void 전제_Redis_를_쓸_수_없다() {
        assertThatThrownBy(() -> redis.opsForValue().get("전제-확인"))
                .as("이 테스트의 전제: Redis 를 쓸 수 없다")
                .isInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    void 적재가_실패해도_기동하고_게이지가_0_이_된다() {
        assertThat(loader.loadCenters()).isFalse();
        assertThat(loader.loadCamps()).isFalse();

        assertThat(gauge("fc")).isZero();
        assertThat(gauge("camp")).isZero();
    }

    @Test
    void 권역_조회가_DB_로_성립한다() {
        // 캐시 읽기도 쓰기도 실패하지만 답은 DB 에서 나온다. 예외가 밖으로 나오면 안 된다.
        String geohash5 = anyZoneGeohash();

        Optional<Zone> zone = referenceData.findZone(geohash5);

        assertThat(zone).isPresent();
        assertThat(zone.get().geohash5()).isEqualTo(geohash5);
    }

    @Test
    void 서비스하지_않는_셀은_그대로_비어_있다() {
        // 폴백이 "아무거나 준다" 로 변질되면 NO_ZONE_MATCH 가 사라진다.
        assertThat(referenceData.findZone("zzzzz")).isEmpty();
    }

    @Test
    void 모든_캠프에서_FC_가_선택된다() {
        List<String> failures = new java.util.ArrayList<>();

        for (Camp camp : referenceData.findAllCamps()) {
            OrderToPlan order = new OrderToPlan(UUID.randomUUID(), ServiceTier.SAME_DAY, false,
                    List.of(new OrderLine("SKU-00001", 1)), NOW.plusSeconds(3600));
            List<CandidateFc> candidates = assembler.forCamp(camp, order.lines());

            assertThat(candidates)
                    .as("%s — 거리를 못 얻으면 무한대가 되고, 그러면 대체 FC 가 영원히 안 뽑힌다", camp.code())
                    .allMatch(fc -> fc.distanceFromCampKm() < Double.MAX_VALUE);

            if (!(selection.select(order, camp, candidates) instanceof FcSelectionResult.Selected)) {
                failures.add(camp.code());
            }
        }

        assertThat(failures).as("Redis 가 없다고 배차 불가가 되면 폴백이 아니다").isEmpty();
    }

    @Test
    void 폴백_사용이_메트릭에_남는다() {
        // 정확성은 유지되지만 조용히 지나가서는 안 된다 — 레이트 리밋의 bypassed 와 같은 어휘다.
        Camp camp = referenceData.findAllCamps().getFirst();
        assembler.forCamp(camp, List.of(new OrderLine("SKU-00001", 1)));

        assertThat(counter("fc", "bypassed")).isPositive();
        assertThat(counter("zone", "redis")).as("죽은 Redis 로 답한 조회는 없어야 한다").isZero();
    }

    private double gauge(String index) {
        return meterRegistry.get(GeoMetrics.LOADED_GAUGE).tag("index", index).gauge().value();
    }

    private double counter(String index, String outcome) {
        try {
            return meterRegistry.get(GeoMetrics.LOOKUPS_COUNTER)
                    .tag("index", index).tag("outcome", outcome).counter().count();
        } catch (MeterNotFoundException e) {
            return 0;
        }
    }

    /** 계약 파일이 아니라 DB 에서 하나 뽑는다 — 이 테스트의 관심은 조회 <em>경로</em>다. */
    private String anyZoneGeohash() {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> ((String) entityManager
                        .createNativeQuery("SELECT geohash5 FROM zones ORDER BY geohash5 LIMIT 1")
                        .getSingleResult()).strip());
    }
}
