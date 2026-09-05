package com.dawnline.fulfillment.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dawnline.common.GeoPoint;
import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.FulfillmentCenter;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.Zone;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * GEO 적재 — <strong>실패해도 기동을 막지 않는다</strong>
 * ([ADR-016](docs/adr/ADR-016-readiness-excludes-kafka.md) 후속 정정).
 *
 * <p>적재 완료를 레디니스 조건으로 두면 Redis 장애가 곧 트래픽 차단이 되어, 폴백을 만든 이유가
 * 사라진다. 그래서 실패는 로그 + 게이지 0 이고 다음 주기에 다시 시도한다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("GeoIndexLoader — 적재 실패는 기동을 막지 않는다")
class GeoIndexLoaderTest {

    private static final UUID FC_ID = UUID.randomUUID();
    private static final UUID CAMP_ID = UUID.randomUUID();

    private StringRedisTemplate redis;
    private GeoOperations<String, String> geo;
    private SimpleMeterRegistry registry;
    private StubReferenceData referenceData;
    private GeoIndexLoader loader;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        geo = mock(GeoOperations.class);
        when(redis.opsForGeo()).thenReturn(geo);
        registry = new SimpleMeterRegistry();
        referenceData = new StubReferenceData();
        loader = new GeoIndexLoader(redis, null, referenceData, new GeoMetrics(registry));
    }

    @Test
    void 좌표를_경도_위도_순으로_넣는다() {
        // Redis 의 Point 는 (x=경도, y=위도)다. 뒤집으면 조용히 지구 반대편이 되고,
        // 거리 순위가 통째로 뒤집힌 채로 서비스가 정상처럼 돈다.
        loader.loadCenters();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Point>> members = ArgumentCaptor.forClass(Map.class);
        verify(geo).add(eq(RedisFcDistances.FC_KEY), members.capture());

        Point point = members.getValue().get(FC_ID.toString());
        assertThat(point.getX()).as("x 는 경도").isEqualTo(127.01);
        assertThat(point.getY()).as("y 는 위도").isEqualTo(37.51);
    }

    @Test
    void 적재에_성공하면_게이지가_1_이다() {
        assertThat(loader.loadCenters()).isTrue();
        assertThat(loader.loadCamps()).isTrue();

        assertThat(gauge("fc")).isEqualTo(1);
        assertThat(gauge("camp")).isEqualTo(1);
    }

    @Test
    void 적재에_실패하면_예외를_내지_않고_게이지가_0_이다() {
        when(geo.add(anyString(), anyMembers())).thenThrow(new RedisConnectionFailureException("죽었다"));

        assertThat(loader.loadCenters()).isFalse();
        assertThat(gauge("fc")).isZero();
    }

    @Test
    void 대상이_없어도_게이지는_0_이다() {
        // "적재할 것이 없다" 와 "적재에 실패했다" 는 다른 사건이지만, GEO 로 답할 수 없다는
        // 결과는 같다. 게이지가 1 이면 그 사실이 가려진다.
        referenceData.centers = List.of();

        assertThat(loader.loadCenters()).isFalse();
        assertThat(gauge("fc")).isZero();
    }

    @Test
    void 소유한_연결만_닫는다() throws Exception {
        // 공유 템플릿을 쓰는 구성(null)에서는 닫을 것이 없다 — 닫으면 핫패스 연결이 끊긴다.
        loader.destroy();

        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        new GeoIndexLoader(redis, () -> closed.set(true), referenceData, new GeoMetrics(registry)).destroy();

        assertThat(closed).isTrue();
    }

    @Test
    void 주기_실행은_두_인덱스를_모두_시도한다() {
        loader.reload();

        verify(geo).add(eq(RedisFcDistances.FC_KEY), anyMembers());
        verify(geo).add(eq(GeoIndexLoader.CAMP_KEY), anyMembers());
    }

    /** {@code Map<String, Point>} 매처. raw 타입으로 쓰면 -Werror 에 걸린다. */
    @SuppressWarnings("unchecked")
    private static Map<String, Point> anyMembers() {
        return any(Map.class);
    }

    private double gauge(String index) {
        return registry.get(GeoMetrics.LOADED_GAUGE).tag("index", index).gauge().value();
    }

    /** 참조 데이터 스텁. */
    private static final class StubReferenceData implements ReferenceData {

        private List<FulfillmentCenter> centers = List.of(new FulfillmentCenter(
                FC_ID, "FC-A", new GeoPoint(37.51, 127.01), true, Set.of(ServiceTier.DAWN), true));

        @Override
        public Optional<Zone> findZone(String geohash5) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Camp> findCamp(UUID campId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<FulfillmentCenter> findAllCenters() {
            return centers;
        }

        @Override
        public List<Camp> findAllCamps() {
            return List.of(new Camp(CAMP_ID, "CAMP-A", FC_ID, new GeoPoint(37.50, 127.00), true));
        }

        @Override
        public Map<UUID, Map<String, Integer>> findStock(Collection<String> skus) {
            throw new UnsupportedOperationException();
        }
    }
}
