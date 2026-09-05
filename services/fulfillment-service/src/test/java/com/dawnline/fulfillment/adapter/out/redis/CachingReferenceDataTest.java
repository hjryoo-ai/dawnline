package com.dawnline.fulfillment.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.FulfillmentCenter;
import com.dawnline.fulfillment.domain.Zone;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 권역 캐시 — <strong>없어도 되고, 틀려서는 안 된다</strong> (§7.2, 불변규칙 7).
 *
 * <p>캐시가 통째로 사라져도 답은 DB 에서 나온다. 반대로 캐시가 <em>틀린</em> 답을 주는 경우는
 * 없어야 하므로, 형식이 어긋난 값은 미스로 취급하고 DB 로 간다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("CachingReferenceData — 캐시는 없어도 되고 틀려서는 안 된다")
class CachingReferenceDataTest {

    private static final String GEOHASH5 = "wydm7";
    private static final String KEY = "zone:geohash5:wydm7";
    private static final UUID ZONE_ID = UUID.randomUUID();
    private static final UUID CAMP_ID = UUID.randomUUID();
    private static final Zone ZONE = new Zone(ZONE_ID, GEOHASH5, CAMP_ID);

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SimpleMeterRegistry registry;
    private StubDelegate delegate;
    private CachingReferenceData cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        registry = new SimpleMeterRegistry();
        delegate = new StubDelegate();
        cache = new CachingReferenceData(delegate, redis, new GeoMetrics(registry), Duration.ofMinutes(10));
    }

    @Test
    void 캐시가_맞으면_DB_에_가지_않는다() {
        when(values.get(KEY)).thenReturn(ZONE_ID + ":" + CAMP_ID);

        assertThat(cache.findZone(GEOHASH5)).contains(ZONE);
        assertThat(delegate.zoneCalls).isZero();
        assertThat(counter("redis")).isEqualTo(1);
    }

    @Test
    void 캐시가_비면_DB_에서_읽고_채운다() {
        when(values.get(KEY)).thenReturn(null);
        delegate.zone = Optional.of(ZONE);

        assertThat(cache.findZone(GEOHASH5)).contains(ZONE);
        verify(values).set(eq(KEY), eq(ZONE_ID + ":" + CAMP_ID), eq(Duration.ofMinutes(10)));
        assertThat(counter("bypassed")).isEqualTo(1);
    }

    @Test
    void 형식이_어긋난_값은_미스로_본다() {
        // 캐시가 틀린 답을 주는 것보다 한 번 더 읽는 편이 낫다.
        when(values.get(KEY)).thenReturn("zoneId만-있고-캠프가-없다");
        delegate.zone = Optional.of(ZONE);

        assertThat(cache.findZone(GEOHASH5)).contains(ZONE);
        assertThat(delegate.zoneCalls).isEqualTo(1);
    }

    @Test
    void 읽기_실패는_밖으로_나가지_않는다() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("죽었다"));
        delegate.zone = Optional.of(ZONE);

        assertThat(cache.findZone(GEOHASH5)).contains(ZONE);
    }

    @Test
    void 쓰기_실패도_밖으로_나가지_않는다() {
        when(values.get(KEY)).thenReturn(null);
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("죽었다"))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        delegate.zone = Optional.of(ZONE);

        assertThat(cache.findZone(GEOHASH5)).contains(ZONE);
    }

    @Test
    void 없는_권역은_캐시에_넣지_않는다() {
        // 넣으면 "서비스하지 않는 지역" 이 10분간 굳는다. 시드를 고친 직후가 그 순간이다.
        when(values.get(KEY)).thenReturn(null);
        delegate.zone = Optional.empty();

        assertThat(cache.findZone(GEOHASH5)).isEmpty();
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 나머지_조회는_그대로_위임한다() {
        assertThat(cache.findCamp(CAMP_ID)).isEmpty();
        assertThat(cache.findAllCenters()).isEmpty();
        assertThat(cache.findAllCamps()).isEmpty();
        assertThat(cache.findStock(List.of("SKU-1"))).isEmpty();
        assertThat(delegate.otherCalls).isEqualTo(4);
    }

    private double counter(String outcome) {
        var found = registry.find(GeoMetrics.LOOKUPS_COUNTER)
                .tag("index", "zone").tag("outcome", outcome).counter();
        return found == null ? 0 : found.count();
    }

    /** DB 조회 스텁. 호출 횟수를 센다 — 캐시가 실제로 왕복을 줄이는지가 관심이다. */
    private static final class StubDelegate implements ReferenceData {

        private Optional<Zone> zone = Optional.empty();
        private int zoneCalls;
        private int otherCalls;

        @Override
        public Optional<Zone> findZone(String geohash5) {
            zoneCalls++;
            return zone;
        }

        @Override
        public Optional<Camp> findCamp(UUID campId) {
            otherCalls++;
            return Optional.empty();
        }

        @Override
        public List<FulfillmentCenter> findAllCenters() {
            otherCalls++;
            return List.of();
        }

        @Override
        public List<Camp> findAllCamps() {
            otherCalls++;
            return List.of();
        }

        @Override
        public Map<UUID, Map<String, Integer>> findStock(Collection<String> skus) {
            otherCalls++;
            return Map.of();
        }
    }
}
