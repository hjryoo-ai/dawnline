package com.dawnline.fulfillment.config;

import com.dawnline.fulfillment.adapter.out.persistence.HaversineFcDistances;
import com.dawnline.fulfillment.adapter.out.persistence.JdbcReferenceData;
import com.dawnline.fulfillment.adapter.out.redis.CachingReferenceData;
import com.dawnline.fulfillment.adapter.out.redis.GeoIndexLoader;
import com.dawnline.fulfillment.adapter.out.redis.GeoMetrics;
import com.dawnline.fulfillment.adapter.out.redis.RedisFcDistances;
import com.dawnline.fulfillment.application.port.out.FcDistances;
import com.dawnline.fulfillment.application.port.out.ReferenceData;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis·참조 데이터 어댑터 배선 (DESIGN.md §5.2, §7.2).
 *
 * <h2>배선 자체가 폴백 구조다</h2>
 * <pre>
 *   ReferenceData  = CachingReferenceData(Redis 캐시) → JdbcReferenceData(DB)
 *   FcDistances    = RedisFcDistances(GEOSEARCH)      → HaversineFcDistances(DB + 메모리)
 * </pre>
 * 두 줄 모두 <strong>Redis 를 앞에, DB 를 뒤에</strong> 두고 데코레이터로 감싼다. Redis 가 없거나
 * 답이 불완전하면 뒤가 답한다(불변규칙 7). 기동은 Redis 에 묶이지 않는다 — Lettuce 는 연결을
 * 지연시키고, 어댑터는 실패를 폴백으로 바꾼다([ADR-016](docs/adr/ADR-016-readiness-excludes-kafka.md)
 * 후속 정정: GEO 적재는 레디니스 조건이 아니다).
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    /**
     * 명령 타임아웃을 짧게 잡는다 (§7.2).
     *
     * <p>커스터마이저를 쓰는 이유는 order-service 와 같다 — 별도 연결 팩토리를 빈으로 올리면
     * Boot 의 기본 팩토리가 {@code @ConditionalOnMissingBean} 때문에 조용히 사라진다.
     *
     * @param properties {@code dawnline.fulfillment.redis.*}
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer redisCommandTimeoutCustomizer(
            FulfillmentProperties properties) {
        return builder -> builder.commandTimeout(properties.redis().commandTimeout());
    }

    /**
     * GEO 메트릭 (§9.1).
     *
     * @param registry 미터 레지스트리
     */
    @Bean
    public GeoMetrics geoMetrics(MeterRegistry registry) {
        return new GeoMetrics(registry);
    }

    /**
     * 참조 데이터 — Redis 권역 캐시 + DB.
     *
     * @param entityManagerFactory EMF
     * @param redis                문자열 전용 템플릿
     * @param metrics              캐시 우회 카운터
     * @param properties           {@code dawnline.fulfillment.redis.*}
     */
    @Bean
    public ReferenceData referenceData(EntityManagerFactory entityManagerFactory,
            StringRedisTemplate redis, GeoMetrics metrics, FulfillmentProperties properties) {

        ReferenceData database = new JdbcReferenceData(
                SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
        return new CachingReferenceData(database, redis, metrics, properties.redis().zoneCacheTtl());
    }

    /**
     * 캠프 기준 FC 거리 — {@code GEOSEARCH} + 하버사인 폴백 (§5.2 5단계, §7.2).
     *
     * @param redis         문자열 전용 템플릿
     * @param referenceData FC 좌표 출처 (폴백이 쓴다)
     * @param metrics       GEO 메트릭
     * @param properties    {@code dawnline.fulfillment.geo.*}
     */
    @Bean
    public FcDistances fcDistances(StringRedisTemplate redis, ReferenceData referenceData,
            GeoMetrics metrics, FulfillmentProperties properties) {

        return new RedisFcDistances(redis, new HaversineFcDistances(referenceData), metrics,
                properties.geo().catalogRadiusKm());
    }

    /**
     * GEO 인덱스 적재 — best-effort + 주기 재시도 (ADR-016 후속 정정).
     *
     * @param redis         문자열 전용 템플릿
     * @param referenceData FC·캠프 좌표 출처
     * @param metrics       적재 상태 게이지
     */
    @Bean
    public GeoIndexLoader geoIndexLoader(StringRedisTemplate redis, ReferenceData referenceData,
            GeoMetrics metrics) {
        return new GeoIndexLoader(redis, referenceData, metrics);
    }
}
