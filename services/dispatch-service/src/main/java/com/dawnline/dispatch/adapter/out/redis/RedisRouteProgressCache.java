package com.dawnline.dispatch.adapter.out.redis;

import com.dawnline.dispatch.application.port.out.RouteProgress;
import com.dawnline.dispatch.application.port.out.RouteProgressCache;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code route:{id}:progress} HASH (DESIGN.md §7.2, TTL 2일).
 *
 * <h2>이 어댑터는 예외를 밖으로 내지 않는다</h2>
 * 불변규칙 7 이다 — Redis 가 사라져도 정확성이 유지되어야 하고, 그 폴백은 {@code route_stops}
 * 조회다({@code RouteMutations.progressOf}). 여기서 {@link DataAccessException} 을 올려보내면
 * 캐시 하나가 {@code delivery.status} 소비 전체를 멈춘다 — <strong>진실 저장소가 아닌 것이
 * 진실 저장소처럼 실패하는 모양</strong>이다.
 *
 * <p>대신 <strong>조용히 넘어가지 않는다.</strong> 읽기가 우회된 것은 {@code debug} 로 남기고,
 * 세는 것은 이 클래스가 아니라 읽는 쪽이다 — 캐시 미스와 Redis 장애를 구별해야 하는 곳이
 * 거기이기 때문이다.
 */
public class RedisRouteProgressCache implements RouteProgressCache {

    private static final Logger log = LoggerFactory.getLogger(RedisRouteProgressCache.class);

    /** §7.2 의 TTL. 라우트 하루치보다 길고 보존 정책보다 짧다. */
    public static final Duration TTL = Duration.ofDays(2);

    /** 해시 필드 — 아직 종결되지 않은 가장 작은 {@code seq}. 남은 stop 이 없으면 빈 문자열이다. */
    public static final String FIELD_NEXT_SEQ = "nextSeq";

    /** 해시 필드 — {@code COMPLETED} 인 stop 수. */
    public static final String FIELD_COMPLETED = "completed";

    /** 해시 필드 — {@code FAILED} 인 stop 수. */
    public static final String FIELD_FAILED = "failed";

    private final StringRedisTemplate redis;

    /**
     * @param redis 문자열 템플릿
     */
    public RedisRouteProgressCache(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    /**
     * 키 이름 — §7.2 의 표기 그대로.
     *
     * @param routeId 라우트 id
     * @return 키
     */
    public static String key(UUID routeId) {
        return "route:" + routeId + ":progress";
    }

    @Override
    public void put(UUID routeId, RouteProgress progress) {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(progress, "progress");
        try {
            String key = key(routeId);
            // 「남은 stop 이 없다」를 빈 문자열로 적는다. 필드를 지우는 것과 같은 뜻이지만,
            // 지우면 «아직 안 쓴 것» 과 «끝난 것» 이 같은 모양이 된다 — 부재는 값이 아니다.
            redis.opsForHash().putAll(key, Map.of(
                    FIELD_NEXT_SEQ, progress.nextSeq() == null ? "" : progress.nextSeq().toString(),
                    FIELD_COMPLETED, Integer.toString(progress.completed()),
                    FIELD_FAILED, Integer.toString(progress.failed())));
            redis.expire(key, TTL);
        } catch (DataAccessException e) {
            log.debug("진행 캐시를 쓰지 못했다. routeId={}", routeId, e);
        }
    }

    @Override
    public Optional<RouteProgress> get(UUID routeId) {
        Objects.requireNonNull(routeId, "routeId");
        try {
            Map<Object, Object> hash = redis.opsForHash().entries(key(routeId));
            if (hash.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new RouteProgress(
                    optionalInt(hash.get(FIELD_NEXT_SEQ)),
                    requiredInt(hash.get(FIELD_COMPLETED)),
                    requiredInt(hash.get(FIELD_FAILED))));
        } catch (DataAccessException | IllegalArgumentException e) {
            // IllegalArgumentException 은 남의 손이 닿은 해시다. 둘을 같이 잡는 이유는 호출자가
            // 할 일이 같기 때문이다 — DB 로 간다.
            log.debug("진행 캐시를 읽지 못했다. routeId={}", routeId, e);
            return Optional.empty();
        }
    }

    private static @Nullable Integer optionalInt(@Nullable Object value) {
        String text = value == null ? "" : value.toString();
        return text.isEmpty() ? null : Integer.valueOf(text);
    }

    private static int requiredInt(@Nullable Object value) {
        if (value == null) {
            throw new IllegalArgumentException("진행 해시에 칸이 빠져 있다");
        }
        return Integer.parseInt(value.toString());
    }
}
