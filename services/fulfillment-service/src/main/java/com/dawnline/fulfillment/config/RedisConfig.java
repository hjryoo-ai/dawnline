package com.dawnline.fulfillment.config;

import com.dawnline.fulfillment.adapter.out.persistence.HaversineFcDistances;
import com.dawnline.fulfillment.adapter.out.persistence.JdbcReferenceData;
import com.dawnline.fulfillment.adapter.out.redis.CachingReferenceData;
import com.dawnline.fulfillment.adapter.out.redis.GeoIndexLoader;
import com.dawnline.fulfillment.adapter.out.redis.GeoMetrics;
import com.dawnline.fulfillment.adapter.out.redis.RedisFcDistances;
import com.dawnline.fulfillment.adapter.out.redis.RedisWaveLock;
import com.dawnline.fulfillment.application.port.out.FcDistances;
import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.fulfillment.application.port.out.WaveLock;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
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
     * 웨이브 마감 락 (§5.2, §7.2 {@code lock:wave:{id}}).
     *
     * <p>이 락은 정확성의 근거가 아니라 <strong>낭비를 줄이는 방어</strong>다. 중복 마감을 실제로
     * 막는 것은 {@code FOR UPDATE} 와 상태 전이이므로, Redis 장애 때는 스킵이 아니라 진행한다
     * (불변규칙 7) — 스킵하면 Redis 장애가 곧 마감 중단이 되고, 마감이 멈추면 계획이 시작되지
     * 않는다.
     *
     * @param redis      문자열 전용 템플릿
     * @param metrics    fail-open 을 세는 메트릭
     * @param properties {@code dawnline.fulfillment.wave.lock-ttl}
     */
    @Bean
    public WaveLock waveLock(StringRedisTemplate redis, GeoMetrics metrics,
            FulfillmentProperties properties) {
        return new RedisWaveLock(redis, metrics, properties.wave().lockTtl());
    }

    /**
     * GEO 인덱스 적재 — best-effort + 주기 재시도 (ADR-016 후속 정정).
     *
     * <h2>왜 전용 연결을 만드는가</h2>
     * 적재는 핫패스가 아니고 그 자리에는 폴백도 없다(적재가 실패하면 <em>이후 조회</em>가 폴백을
     * 탄다). 핫패스 예산 50 ms 를 그대로 쓰면 첫 명령에 연결 수립이 포함되는 느린 환경에서 매번
     * 첫 시도가 실패하고, 재시도가 있어 동작은 하지만 그 실패 로그가 진짜 장애를 가린다.
     *
     * <p>연결 팩토리를 <strong>빈으로 올리지 않는다.</strong> Boot 의 팩토리는
     * {@code @ConditionalOnMissingBean(RedisConnectionFactory.class)} 라, 타입이 같은 빈을 하나 더
     * 올리면 <em>Boot 의 것이 조용히 사라지고</em> 이 팩토리가 전부를 맡게 된다 — 핫패스까지
     * 2초 예산으로 도는 정반대의 결과다. 그래서 이 메서드가 만들어 로더에게 소유시키고,
     * 로더가 {@code DisposableBean} 으로 닫는다.
     *
     * @param connectionDetails 주소 출처. {@code spring.data.redis.url} 도 여기로 들어온다
     * @param referenceData     FC·캠프 좌표 출처
     * @param metrics           적재 상태 게이지
     * @param properties        {@code dawnline.fulfillment.redis.load-command-timeout}
     */
    @Bean
    public GeoIndexLoader geoIndexLoader(DataRedisConnectionDetails connectionDetails,
            ReferenceData referenceData, GeoMetrics metrics, FulfillmentProperties properties) {

        DataRedisConnectionDetails.Standalone standalone = connectionDetails.getStandalone();
        RedisStandaloneConfiguration server =
                new RedisStandaloneConfiguration(standalone.getHost(), standalone.getPort());
        server.setDatabase(standalone.getDatabase());
        if (connectionDetails.getUsername() != null) {
            server.setUsername(connectionDetails.getUsername());
        }
        if (connectionDetails.getPassword() != null) {
            server.setPassword(connectionDetails.getPassword());
        }

        LettuceConnectionFactory factory = new LettuceConnectionFactory(server,
                LettuceClientConfiguration.builder()
                        .commandTimeout(properties.redis().loadCommandTimeout())
                        .build());
        factory.afterPropertiesSet();
        factory.start();

        return new GeoIndexLoader(new StringRedisTemplateFor(factory), factory::destroy,
                referenceData, metrics);
    }

    /** 팩토리를 받아 바로 쓸 수 있게 초기화한 템플릿. {@code afterPropertiesSet} 을 잊지 않기 위한 것이다. */
    private static final class StringRedisTemplateFor extends StringRedisTemplate {

        private StringRedisTemplateFor(LettuceConnectionFactory factory) {
            super(factory);
            afterPropertiesSet();
        }
    }
}
