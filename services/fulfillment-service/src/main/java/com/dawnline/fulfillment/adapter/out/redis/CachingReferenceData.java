package com.dawnline.fulfillment.adapter.out.redis;

import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.FulfillmentCenter;
import com.dawnline.fulfillment.domain.Zone;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 권역 조회에 Redis 캐시를 얹은 {@link ReferenceData} (§7.2 {@code zone:geohash5:{p}}, TTL 10m).
 *
 * <p>나머지 메서드는 그대로 위임한다. 캐시를 씌운 것은 권역 하나뿐인데, 그것이
 * <strong>주문마다</strong> 불리는 유일한 참조 조회이기 때문이다 — FC 목록과 재고는 판정 한 번에
 * 한 번씩만 읽고, 캠프는 PK 조회다.
 *
 * <h2>값이 {@code zoneId} 만이 아닌 이유</h2>
 * 호출부는 권역 다음에 곧바로 캠프를 필요로 한다(§5.2 4단계). zoneId 만 캐시하면 캐시가 맞아도
 * 캠프를 얻으려 DB 를 한 번 더 가야 해서 왕복이 줄지 않는다 — 캐시가 없는 것과 같아진다.
 * 그래서 값은 {@code zoneId:campId} 다(§5.2, §7.2).
 *
 * <h2>캐시는 진실이 아니다</h2>
 * 읽기 실패도, 쓰기 실패도 삼킨다(불변규칙 7). 캐시가 통째로 사라져도 답은 DB 에서 나온다.
 * <strong>다만 캐시가 <em>틀린</em> 답을 주는 경우는 없어야 한다</strong> — 그래서 형식이 어긋난
 * 값은 캐시 미스로 취급하고 DB 로 간다. 참조 데이터가 바뀌면(시드 재적용) TTL 10분 안에 낡은
 * 값이 남을 수 있는데, 권역→캠프 매핑을 바꾸는 것은 운영 작업이므로 그때는 키를 지운다(RB-03).
 */
public class CachingReferenceData implements ReferenceData {

    private static final Logger log = LoggerFactory.getLogger(CachingReferenceData.class);

    /** §7.2 의 키 접두어. */
    public static final String KEY_PREFIX = "zone:geohash5:";

    private final ReferenceData delegate;
    private final StringRedisTemplate redis;
    private final GeoMetrics metrics;
    private final Duration ttl;

    /**
     * @param delegate DB 조회
     * @param redis    문자열 전용 템플릿
     * @param metrics  캐시 우회를 세는 메트릭
     * @param ttl      캐시 TTL (§7.2 기본 10분)
     */
    public CachingReferenceData(ReferenceData delegate, StringRedisTemplate redis, GeoMetrics metrics,
            Duration ttl) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.redis = Objects.requireNonNull(redis, "redis");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    @Override
    public Optional<Zone> findZone(String geohash5) {
        Objects.requireNonNull(geohash5, "geohash5");
        Optional<Zone> cached = readCache(geohash5);
        if (cached.isPresent()) {
            metrics.servedByRedis("zone");
            return cached;
        }
        metrics.servedByFallback("zone");
        Optional<Zone> found = delegate.findZone(geohash5);
        found.ifPresent(this::writeCache);
        return found;
    }

    private Optional<Zone> readCache(String geohash5) {
        try {
            String value = redis.opsForValue().get(KEY_PREFIX + geohash5);
            if (value == null) {
                return Optional.empty();
            }
            int separator = value.indexOf(':');
            if (separator < 0) {
                return Optional.empty();
            }
            return Optional.of(new Zone(
                    UUID.fromString(value.substring(0, separator)),
                    geohash5,
                    UUID.fromString(value.substring(separator + 1))));
        } catch (RuntimeException e) {
            // 형식 오류든 연결 오류든 결과는 같다 — DB 로 간다. 밖으로 내지 않는다.
            log.debug("권역 캐시 읽기 실패. DB 로 갑니다. geohash5={}", geohash5, e);
            return Optional.empty();
        }
    }

    private void writeCache(Zone zone) {
        try {
            redis.opsForValue().set(KEY_PREFIX + zone.geohash5(),
                    zone.id() + ":" + zone.campId(), ttl);
        } catch (RuntimeException e) {
            log.debug("권역 캐시 쓰기 실패. 무시합니다. geohash5={}", zone.geohash5(), e);
        }
    }

    @Override
    public Optional<Camp> findCamp(UUID campId) {
        return delegate.findCamp(campId);
    }

    @Override
    public List<FulfillmentCenter> findAllCenters() {
        return delegate.findAllCenters();
    }

    @Override
    public List<Camp> findAllCamps() {
        return delegate.findAllCamps();
    }

    @Override
    public Map<UUID, Map<String, Integer>> findStock(Collection<String> skus) {
        return delegate.findStock(skus);
    }
}
