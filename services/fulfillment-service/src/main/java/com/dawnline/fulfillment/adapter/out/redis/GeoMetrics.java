package com.dawnline.fulfillment.adapter.out.redis;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GEO 관련 메트릭 (DESIGN.md §9.1, [ADR-016](docs/adr/ADR-016-readiness-excludes-kafka.md) 후속 정정).
 *
 * <h2>왜 이 둘이 필요한가</h2>
 * GEO 적재는 레디니스 조건이 아니다 — 폴백이 있는 의존성을 레디니스에 넣으면 Redis 장애가 곧
 * 트래픽 차단이 되어 폴백을 만든 이유가 사라진다. 대신 대가를 갚는 방식은 Kafka 때와 같다:
 * <strong>프로브가 녹색이어도 무언가 빠져 있다는 사실이 어딘가에 드러나야 한다.</strong>
 *
 * <ul>
 *   <li>{@code dawnline_geo_index_loaded{index}} — 적재 성공 여부 0/1. 0 이어도 서비스는 정상이다</li>
 *   <li>{@code dawnline_geo_lookups_total{index,outcome}} — {@code bypassed} 는 Redis 를 건너뛰고
 *       DB 폴백으로 답한 것이다. 레이트 리밋의 {@code bypassed}(§7.2)와 같은 어휘를 쓴다</li>
 * </ul>
 *
 * <p>Prometheus 는 같은 이름의 미터가 <strong>같은 라벨 키 집합</strong>을 갖기를 요구한다. 그래서
 * 두 결과({@code redis}/{@code bypassed}) 모두 같은 두 라벨로 낸다 — 한쪽만 라벨을 더하면 등록이
 * 실패한다(ADR-022 에서 jar 로 확인한 제약).
 */
public class GeoMetrics {

    /** 적재 상태 게이지 이름. */
    public static final String LOADED_GAUGE = "dawnline_geo_index_loaded";

    /** 조회 결과 카운터 이름. */
    public static final String LOOKUPS_COUNTER = "dawnline_geo_lookups_total";

    private final MeterRegistry registry;
    private final Map<String, AtomicInteger> loaded = new ConcurrentHashMap<>();

    /**
     * @param registry 미터 레지스트리
     */
    public GeoMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * 적재 상태를 기록한다.
     *
     * @param index   {@code fc} 또는 {@code camp}
     * @param success 적재에 성공했는가
     */
    public void indexLoaded(String index, boolean success) {
        loaded.computeIfAbsent(index, key -> registry.gauge(LOADED_GAUGE,
                        io.micrometer.core.instrument.Tags.of("index", key), new AtomicInteger()))
                .set(success ? 1 : 0);
    }

    /**
     * Redis 로 답했다.
     *
     * @param index 인덱스 이름
     */
    public void servedByRedis(String index) {
        counter(index, "redis").increment();
    }

    /**
     * Redis 를 건너뛰고 DB 폴백으로 답했다.
     *
     * <p><strong>이 값이 오르는 것은 장애가 아니라 폴백이 도는 중이라는 뜻이다.</strong> 정확성은
     * 유지되지만(불변규칙 7) 조용히 지나가서는 안 된다 — 그래서 센다.
     *
     * @param index 인덱스 이름
     */
    public void servedByFallback(String index) {
        counter(index, "bypassed").increment();
    }

    private Counter counter(String index, String outcome) {
        return Counter.builder(LOOKUPS_COUNTER)
                .tag("index", index)
                .tag("outcome", outcome)
                .register(registry);
    }
}
