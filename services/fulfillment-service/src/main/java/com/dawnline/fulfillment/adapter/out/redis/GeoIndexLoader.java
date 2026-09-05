package com.dawnline.fulfillment.adapter.out.redis;

import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.FulfillmentCenter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@code geo:fc}·{@code geo:camp} 적재 (§5.2, §7.2) — <strong>best-effort</strong>.
 *
 * <h2>기동을 막지 않는다</h2>
 * [ADR-016](docs/adr/ADR-016-readiness-excludes-kafka.md) 후속 정정. 이전 설계는 §8.6 의 레디니스
 * 조건에 "GEO 적재 완료" 를 넣었는데, 그 키에는 폴백(DB 전체 조회 + 메모리 하버사인)이 있으므로
 * <strong>적재 실패는 서비스를 세울 이유가 되지 못한다.</strong> 넣으면 Redis 장애가 곧 트래픽
 * 차단이 되어 폴백을 만든 이유가 사라진다.
 *
 * <p>그래서 실패는 로그 + 게이지 0 이고, 주기적으로 다시 시도한다. 재시도가 안전한 이유는
 * {@code GEOADD} 가 멱등이기 때문이다 — 같은 멤버를 다시 넣으면 좌표를 덮어쓴다. 참조 데이터가
 * 바뀌었을 때 반영되는 경로이기도 하다.
 *
 * <h2>적재는 핫패스가 아니다 — 타임아웃도 다르다</h2>
 * 이 로더는 {@code dawnline.fulfillment.redis.load-command-timeout}(기본 2초)을 쓰는 <strong>전용
 * 연결</strong>로 돈다. 핫패스 예산 50 ms 는 {@code order.placed} 를 소비하며 부르는
 * {@code GEOSEARCH} 를 위한 값이고(§7.2 — 그 자리에는 폴백이 있다), 적재에 그 예산을 쓰면 첫
 * 명령에 연결 수립이 포함되는 느린 환경에서 <em>매번</em> 첫 시도가 실패한다. 재시도가 있어
 * 동작은 하지만, <strong>그 실패 로그가 진짜 장애를 가리기 시작한다.</strong>
 *
 * <h2>이 클래스가 세는 것</h2>
 * {@code dawnline_geo_index_loaded{index}} 가 0 이면 "폴백으로 돌고 있다" 는 뜻이다. 서비스는
 * 정상이지만 그 사실이 조용해서는 안 된다(§9.1).
 */
public class GeoIndexLoader implements org.springframework.beans.factory.DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GeoIndexLoader.class);

    /** §7.2 의 키. */
    public static final String CAMP_KEY = "geo:camp";

    private final StringRedisTemplate redis;
    private final ReferenceData referenceData;
    private final GeoMetrics metrics;
    private final @Nullable AutoCloseable ownedConnections;

    /**
     * @param redis            문자열 전용 템플릿. <strong>적재 전용 타임아웃</strong>을 가진
     *                         연결을 쓴다 — 핫패스 예산(50 ms)은 이 자리에 맞지 않는다
     * @param ownedConnections 이 로더가 소유한 연결 자원. 종료 시 닫는다. 공유 템플릿을 쓰면 {@code null}
     * @param referenceData    FC·캠프 좌표 출처
     * @param metrics          적재 상태 게이지
     */
    public GeoIndexLoader(StringRedisTemplate redis, @Nullable AutoCloseable ownedConnections,
            ReferenceData referenceData, GeoMetrics metrics) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.ownedConnections = ownedConnections;
        this.referenceData = Objects.requireNonNull(referenceData, "referenceData");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** 소유한 연결을 닫는다. 이 로더만 쓰는 연결이므로 컨텍스트와 함께 정리한다. */
    @Override
    public void destroy() throws Exception {
        if (ownedConnections != null) {
            ownedConnections.close();
        }
    }

    /**
     * 기동 직후 한 번, 그 뒤 주기적으로 적재한다.
     *
     * <p>{@code initialDelay} 를 0 으로 두는 이유: 첫 주문이 오기 전에 적재를 시도하는 편이 낫다.
     * 실패해도 기동은 이미 끝났으므로 잃는 것이 없다.
     */
    @Scheduled(
            fixedDelayString = "${dawnline.fulfillment.geo.reload-interval-ms:300000}",
            initialDelayString = "${dawnline.fulfillment.geo.initial-delay-ms:0}")
    public void reload() {
        loadCenters();
        loadCamps();
    }

    /**
     * {@code geo:fc} 적재.
     *
     * @return 성공 여부
     */
    public boolean loadCenters() {
        Map<String, Point> members = new LinkedHashMap<>();
        for (FulfillmentCenter center : referenceData.findAllCenters()) {
            // Redis 의 Point 는 (x=경도, y=위도) 순서다. 뒤집으면 조용히 지구 반대편이 된다.
            members.put(center.id().toString(),
                    new Point(center.location().lng(), center.location().lat()));
        }
        return load(RedisFcDistances.FC_KEY, "fc", members);
    }

    /**
     * {@code geo:camp} 적재.
     *
     * <p>지금 이 키를 읽는 코드는 없다 — §7.2 가 정의한 자리이고 소비자는 Phase 3(계획 시 캠프
     * 근접 조회)·Phase 6(운영 화면)이다. 적재는 여기서 함께 하고, 읽는 쪽이 생길 때 그 어댑터가
     * 같은 폴백 규칙을 따른다.
     *
     * @return 성공 여부
     */
    public boolean loadCamps() {
        Map<String, Point> members = new LinkedHashMap<>();
        for (Camp camp : referenceData.findAllCamps()) {
            members.put(camp.id().toString(), new Point(camp.location().lng(), camp.location().lat()));
        }
        return load(CAMP_KEY, "camp", members);
    }

    private boolean load(String key, String index, Map<String, Point> members) {
        if (members.isEmpty()) {
            // 참조 데이터가 비었다. 적재할 것이 없는 것과 실패는 다르다 — 게이지를 0 으로 두어
            // "GEO 로 답할 수 없다" 는 사실을 그대로 남긴다.
            log.warn("{} 적재 대상이 없습니다. 시드가 비어 있는지 확인하세요.", key);
            metrics.indexLoaded(index, false);
            return false;
        }
        try {
            redis.opsForGeo().add(key, members);
            metrics.indexLoaded(index, true);
            log.info("{} 적재 완료 ({}건)", key, members.size());
            return true;
        } catch (RuntimeException e) {
            metrics.indexLoaded(index, false);
            log.warn("{} 적재 실패. 폴백으로 동작하며 다음 주기에 다시 시도합니다.", key, e);
            return false;
        }
    }
}
