package com.dawnline.tracking.adapter.out.redis;

import com.dawnline.tracking.application.TrackingMetrics;
import com.dawnline.tracking.application.port.out.AtRiskCooldown;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link AtRiskCooldown} 의 Redis 구현 — {@code SET route:{id}:atrisk:cooldown 1 NX PX 300000}
 * (§7.2, §5.4).
 *
 * <h2>Redis 장애는 fail-open 이다</h2>
 * 창을 <em>얻지 못한 것</em>과 Redis 가 <em>죽은 것</em>은 다르다. 전자는 「방금 알렸다」는
 * 뜻이라 건너뛰지만, 후자에 건너뛰면 <strong>Redis 장애가 곧 위험 감지 중단</strong>이 된다 —
 * 그리고 그것은 §7.2 가 이 키에 대해 「중복 허용」이라고 적어 둔 것의 정반대다. 불변규칙 7 은
 * 「어떤 키가 사라져도 DB 만으로 정확성이 유지된다」인데, 여기서 정확성은 <em>알림이 나가는
 * 것</em>이지 나가지 않는 것이 아니다.
 *
 * <p>값을 토큰으로 두지 않는다. {@code RedisWaveLock} 과 달리 이 창은 <strong>해제하지
 * 않는다</strong> — TTL 이 곧 정책이다. 해제할 일이 없으면 「내가 잡은 락인가」를 물을 일도 없다.
 *
 * <p>폴백은 조용히 일어나면 안 된다(§9.1 {@code dawnline_at_risk_cooldown_bypassed_total}).
 * 이 값이 오르는 동안 at-risk 는 쿨다운 없이 나가고 있고, 그 사실이 안 보이면 「알림이 늘었다」가
 * 위험이 늘어난 것으로 읽힌다.
 */
public class RedisAtRiskCooldown implements AtRiskCooldown {

    private static final Logger log = LoggerFactory.getLogger(RedisAtRiskCooldown.class);

    /** §7.2 의 키 형태. */
    public static final String KEY_PREFIX = "route:";

    /** §7.2 의 키 형태. */
    public static final String KEY_SUFFIX = ":atrisk:cooldown";

    /** 값은 쓰이지 않는다 — 있고 없음이 전부다. */
    private static final String MARK = "1";

    private final StringRedisTemplate redis;
    private final TrackingMetrics metrics;
    private final Duration window;

    /**
     * @param redis   문자열 전용 템플릿
     * @param metrics 폴백(fail-open)을 세는 메트릭
     * @param window  쿨다운 창 (§5.4 기본 5분)
     */
    public RedisAtRiskCooldown(StringRedisTemplate redis, TrackingMetrics metrics, Duration window) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.window = Objects.requireNonNull(window, "window");
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window 는 양수여야 합니다: " + window);
        }
    }

    @Override
    public boolean tryStart(UUID routeId) {
        Objects.requireNonNull(routeId, "routeId");
        try {
            return Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent(keyOf(routeId), MARK, window));
        } catch (RuntimeException e) {
            // 건너뛰면 위험을 놓친다. 알림이 시끄러워지는 쪽을 고른다 (§7.2).
            log.warn("at-risk 쿨다운을 쓸 수 없어 쿨다운 없이 발행합니다. routeId={}", routeId, e);
            metrics.countCooldownBypassed();
            return true;
        }
    }

    /** §7.2 의 {@code route:{id}:atrisk:cooldown}. */
    static String keyOf(UUID routeId) {
        return KEY_PREFIX + routeId + KEY_SUFFIX;
    }
}
