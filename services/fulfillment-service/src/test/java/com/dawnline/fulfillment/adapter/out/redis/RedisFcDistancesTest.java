package com.dawnline.fulfillment.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dawnline.common.GeoPoint;
import com.dawnline.fulfillment.application.port.out.FcDistances;
import com.dawnline.fulfillment.domain.Camp;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code GEOSEARCH} 어댑터 — <strong>폴백이 언제 도는가</strong>.
 *
 * <p>실물 Redis 와의 동등성은 {@code GeoEquivalenceIT} 가 본다. 여기서 보는 것은 그 앞의 판단이다:
 * 실패했을 때, 그리고 <em>불완전하게 성공했을 때</em> 폴백으로 넘어가는가.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("RedisFcDistances — 실패도 불완전도 폴백으로 간다")
class RedisFcDistancesTest {

    private static final UUID FC_A = UUID.randomUUID();
    private static final UUID FC_B = UUID.randomUUID();
    private static final Camp CAMP = new Camp(UUID.randomUUID(), "CAMP-A", FC_A,
            new GeoPoint(37.50, 127.00), true);
    private static final Map<UUID, Double> FALLBACK_ANSWER = Map.of(FC_A, 1.0, FC_B, 2.0);

    private StringRedisTemplate redis;
    private GeoOperations<String, String> geo;
    private SimpleMeterRegistry registry;
    private RedisFcDistances distances;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        geo = mock(GeoOperations.class);
        when(redis.opsForGeo()).thenReturn(geo);
        registry = new SimpleMeterRegistry();
        FcDistances fallback = (camp, ids) -> FALLBACK_ANSWER;
        distances = new RedisFcDistances(redis, fallback, new GeoMetrics(registry), 500.0);
    }

    @Test
    void 전부_찾으면_Redis_결과를_쓴다() {
        stubSearch(results(Map.of(FC_A, 1.4, FC_B, 9.7)));

        Map<UUID, Double> result = distances.fromCamp(CAMP, List.of(FC_A, FC_B));

        assertThat(result).containsEntry(FC_A, 1.4).containsEntry(FC_B, 9.7);
        assertThat(counter("redis")).isEqualTo(1);
        assertThat(counter("bypassed")).isZero();
    }

    @Test
    void 하나라도_빠지면_폴백으로_간다() {
        // 부분 결과는 폴백보다 나쁘다 — 빠진 FC 는 조용히 후보에서 사라지고, 그러면 같은 주문이
        // 다른 FC 를 받는다. 그것이 이 폴백이 막아야 하는 바로 그 일이다.
        stubSearch(results(Map.of(FC_A, 1.4)));

        Map<UUID, Double> result = distances.fromCamp(CAMP, List.of(FC_A, FC_B));

        assertThat(result).isEqualTo(FALLBACK_ANSWER);
        assertThat(counter("bypassed")).isEqualTo(1);
    }

    @Test
    void 연결_실패는_밖으로_나가지_않는다() {
        // DataAccessException 이 올라가면 주문 처리가 멈춘다 — 불변규칙 7 위반이다.
        when(geo.search(anyString(), any(), any(Distance.class), any()))
                .thenThrow(new RedisConnectionFailureException("죽었다"));

        Map<UUID, Double> result = distances.fromCamp(CAMP, List.of(FC_A, FC_B));

        assertThat(result).isEqualTo(FALLBACK_ANSWER);
        assertThat(counter("bypassed")).isEqualTo(1);
    }

    @Test
    void 결과가_null_이어도_폴백으로_간다() {
        stubSearch(null);

        assertThat(distances.fromCamp(CAMP, List.of(FC_A, FC_B))).isEqualTo(FALLBACK_ANSWER);
    }

    @Test
    void 요청하지_않은_멤버는_버린다() {
        // geo:fc 에 남아 있는 옛 FC 가 후보로 되살아나면 안 된다.
        UUID retired = UUID.randomUUID();
        stubSearch(results(Map.of(FC_A, 1.4, FC_B, 9.7, retired, 0.1)));

        assertThat(distances.fromCamp(CAMP, List.of(FC_A, FC_B))).containsOnlyKeys(FC_A, FC_B);
    }

    private void stubSearch(GeoResults<RedisGeoCommands.GeoLocation<String>> results) {
        when(geo.search(anyString(), any(), any(Distance.class), any())).thenReturn(results);
    }

    private static GeoResults<RedisGeoCommands.GeoLocation<String>> results(Map<UUID, Double> km) {
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = km.entrySet().stream()
                .map(entry -> new GeoResult<>(
                        new RedisGeoCommands.GeoLocation<>(entry.getKey().toString(), null),
                        new Distance(entry.getValue(), Metrics.KILOMETERS)))
                .toList();
        return new GeoResults<>(list);
    }

    private double counter(String outcome) {
        return registry.find(GeoMetrics.LOOKUPS_COUNTER).tag("index", "fc").tag("outcome", outcome)
                .counter() == null ? 0
                : registry.get(GeoMetrics.LOOKUPS_COUNTER).tag("index", "fc").tag("outcome", outcome)
                        .counter().count();
    }
}
