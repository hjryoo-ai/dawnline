package com.dawnline.fulfillment.adapter.out.redis;

import com.dawnline.fulfillment.application.port.out.FcDistances;
import com.dawnline.fulfillment.domain.Camp;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;

/**
 * {@link FcDistances} 의 Redis 구현 — {@code GEOSEARCH geo:fc FROMLONLAT <캠프 좌표>} (§5.2 5단계).
 *
 * <p>거리 기준점이 고객 주소가 아니라 <strong>캠프</strong>인 이유는 ADR-021 결정 3-a 에 있다 —
 * 라스트마일은 어느 FC 를 쓰든 캠프에서 출발하므로 대체 FC 선택에서 달라지는 비용은
 * FC → 캠프 간선(linehaul)뿐이다.
 *
 * <h2>반경 50 km 를 여기서 걸지 않는다</h2>
 * 검색 반경은 <em>카탈로그를 다 담을 만큼</em> 넓게 준다(기본 500 km). §5.2 5단계의 50 km 는
 * <strong>판정</strong>이고 그것은 {@code FcSelection} 안에 있다. 여기서 미리 거르면 반경 밖 FC 가
 * 판정에서 사라져 {@code UNSERVICEABLE} 사유가 달라진다 — "이 티어를 지원하는 FC 가 없다" 와
 * "있지만 너무 멀다" 는 다른 사실이다.
 *
 * <h2>부분 결과는 폴백보다 나쁘다</h2>
 * 적재가 덜 됐거나 일부 멤버가 빠지면 <strong>돌려주지 않고 폴백으로 넘긴다.</strong> FC 하나가
 * 빠진 거리 맵은 조용히 다른 FC 를 고르게 만든다 — 그것이 이 폴백이 막아야 하는 바로 그 일이다
 * (불변규칙 7: 폴백은 "동작한다" 가 아니라 <em>같은 답을 낸다</em>).
 */
public class RedisFcDistances implements FcDistances {

    private static final Logger log = LoggerFactory.getLogger(RedisFcDistances.class);

    /** §7.2 의 키. */
    public static final String FC_KEY = "geo:fc";

    private final StringRedisTemplate redis;
    private final FcDistances fallback;
    private final GeoMetrics metrics;
    private final double catalogRadiusKm;

    /**
     * @param redis           문자열 전용 템플릿
     * @param fallback        DB 전체 조회 + 메모리 하버사인 (§7.2)
     * @param metrics         GEO 메트릭
     * @param catalogRadiusKm 검색 반경(km). 판정의 50 km 가 아니라 <em>카탈로그를 다 담는</em> 값이다
     */
    public RedisFcDistances(StringRedisTemplate redis, FcDistances fallback, GeoMetrics metrics,
            double catalogRadiusKm) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (catalogRadiusKm <= 0) {
            throw new IllegalArgumentException("catalogRadiusKm 은 양수여야 합니다: " + catalogRadiusKm);
        }
        this.catalogRadiusKm = catalogRadiusKm;
    }

    @Override
    public Map<UUID, Double> fromCamp(Camp camp, Collection<UUID> fcIds) {
        Objects.requireNonNull(camp, "camp");
        Set<UUID> wanted = Set.copyOf(Objects.requireNonNull(fcIds, "fcIds"));
        try {
            Map<UUID, Double> distances = search(camp);
            if (distances != null && distances.keySet().containsAll(wanted)) {
                metrics.servedByRedis("fc");
                return retain(distances, wanted);
            }
            log.warn("geo:fc 조회가 불완전합니다(기대 {} / 실제 {}). DB 폴백으로 갑니다. camp={}",
                    wanted.size(), distances == null ? 0 : distances.size(), camp.code());
        } catch (RuntimeException e) {
            // DataAccessException 을 밖으로 내지 않는다 (§13 불변규칙 7 강제 수단).
            log.warn("geo:fc 조회 실패. DB 폴백으로 갑니다. camp={}", camp.code(), e);
        }
        metrics.servedByFallback("fc");
        return fallback.fromCamp(camp, wanted);
    }

    private static Map<UUID, Double> retain(Map<UUID, Double> distances, Set<UUID> wanted) {
        Map<UUID, Double> kept = new LinkedHashMap<>();
        distances.forEach((id, km) -> {
            if (wanted.contains(id)) {
                kept.put(id, km);
            }
        });
        return Map.copyOf(kept);
    }

    private Map<UUID, Double> search(Camp camp) {
        RedisGeoCommands.GeoSearchCommandArgs args = RedisGeoCommands.GeoSearchCommandArgs
                .newGeoSearchArgs()
                .includeDistance()
                .sortAscending();
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redis.opsForGeo().search(
                FC_KEY,
                GeoReference.fromCoordinate(camp.location().lng(), camp.location().lat()),
                new Distance(catalogRadiusKm, Metrics.KILOMETERS),
                args);
        if (results == null) {
            return null;
        }
        Map<UUID, Double> distances = new LinkedHashMap<>();
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
            distances.put(UUID.fromString(result.getContent().getName()), result.getDistance().getValue());
        }
        return Map.copyOf(distances);
    }
}
