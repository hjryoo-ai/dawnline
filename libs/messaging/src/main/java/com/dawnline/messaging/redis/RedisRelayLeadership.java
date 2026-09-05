package com.dawnline.messaging.redis;

import com.dawnline.common.Ids;
import com.dawnline.messaging.outbox.RelayLeadership;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link RelayLeadership} 의 Redis 구현 — {@code SET lock:relay:{service} <token> NX PX <ttl>}
 * (§7.2, [ADR-027](docs/adr/ADR-027-outbox-relay-leader-lock.md)).
 *
 * <h2>Redis 장애는 fail-closed 다 — {@code RedisWaveLock} 과 반대다</h2>
 * 두 락은 같은 자료구조를 쓰지만 <strong>실패했을 때 잃는 것이 다르다.</strong>
 *
 * <ul>
 *   <li>{@code lock:wave} 를 못 잡으면 마감을 건너뛰게 되는데, <strong>컷오프 시각은 지나가면
 *       돌아오지 않는다.</strong> 그래서 fail-open 이고, 중복 마감은 {@code FOR UPDATE} 와 상태
 *       머신이 DB 에서 막는다 — 즉 <em>정확성의 최종 보루가 DB 에 있다</em>(불변규칙 7).</li>
 *   <li>여기서 잃는 것은 <strong>키 단위 순서</strong>이고, DB 에는 그것을 지킬 수단이 없다.
 *       {@code SKIP LOCKED} 는 중복 발행만 막는다. 반면 멈춰서 잃는 것은 <strong>지연뿐</strong>이다 —
 *       {@code outbox_events} 행은 그대로 남고, Redis 가 돌아오면 그대로 이어서 나간다.</li>
 * </ul>
 *
 * 되돌릴 수 없는 손실(순서)과 되돌아오는 손실(지연) 중에서 후자를 고른다. 불변규칙 7 은 "Redis 가
 * 사라져도 DB 로 정확성이 회복되게 하라" 이고, <strong>회복 경로가 없는 자리에서 그 규칙이 요구하는
 * 것은 없는 폴백을 지어내는 것이 아니라 멈추는 것</strong>이다.
 *
 * <p>멈춤은 보이지 않게 두지 않는다. {@code dawnline_outbox_lag_seconds} 가 오르고 §9.4 의
 * "outbox 지연 &gt; 30s" 알림이 그대로 잡는다 — 리더 없음을 위한 새 알림이 필요 없는 이유다.
 * 인스턴스가 하나뿐인 배포에서 이 선택이 부담이면 {@code leader.enabled=false} 로 <em>적어서</em>
 * 끈다({@link RelayLeadership#singleInstance()}).
 *
 * <h2>TTL 은 배치보다 길어야 한다</h2>
 * 리더십은 배치 <em>전에</em> 확인하고 배치 <em>중에는</em> 확인하지 않는다. 그래서 TTL 이 한 배치의
 * 최대 소요(전송 타임아웃)보다 짧으면 배치 도중 리더가 바뀔 수 있다. 그 관계는
 * {@code DawnlineMessagingProperties.Outbox} 가 기동 시 검증한다.
 */
public class RedisRelayLeadership implements RelayLeadership {

    private static final Logger log = LoggerFactory.getLogger(RedisRelayLeadership.class);

    /** §7.2 의 키 접두어. */
    public static final String KEY_PREFIX = "lock:relay:";

    private static final RedisScript<Long> ACQUIRE = script("redis/relay-leader-acquire.lua");
    private static final RedisScript<Long> RELEASE = script("redis/relay-leader-release.lua");

    private final StringRedisTemplate redis;
    private final String key;
    private final String token;
    private final Duration ttl;

    /**
     * @param redis   문자열 전용 템플릿
     * @param service 서비스 이름 (§9.1 의 {@code service} 태그와 같은 값). 키를 만든다
     * @param ttl     리더십 TTL. 리더가 죽으면 이 시간 뒤에 다음 인스턴스가 이어받는다
     */
    public RedisRelayLeadership(StringRedisTemplate redis, String service, Duration ttl) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.key = KEY_PREFIX + Objects.requireNonNull(service, "service");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl 은 양수여야 합니다: " + ttl);
        }
        // 토큰은 인스턴스 수명 동안 고정이다. 매번 새로 만들면 갱신이 늘 실패해 자기 자신에게서
        // 리더십을 빼앗는다.
        this.token = Ids.newId().toString();
    }

    @Override
    public State lead() {
        try {
            Long result = redis.execute(ACQUIRE, List.of(key), token, String.valueOf(ttl.toMillis()));
            return result != null && result == 1L ? State.LEADER : State.FOLLOWER;
        } catch (RuntimeException e) {
            // 판정할 수 없다. FOLLOWER 로 접지 않는다 — 둘은 봐야 할 곳이 다르다.
            log.warn("릴레이 리더십을 판정할 수 없습니다. 발행을 멈춥니다. key={}", key, e);
            return State.UNKNOWN;
        }
    }

    @Override
    public void stepDown() {
        try {
            redis.execute(RELEASE, List.of(key), token);
        } catch (RuntimeException e) {
            log.debug("릴레이 리더십 해제 실패. TTL 이 정리합니다. key={}", key, e);
        }
    }

    /** 이 인스턴스의 리더 키. 테스트와 운영 조회용. */
    public String key() {
        return key;
    }

    private static RedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(read(path));
        script.setResultType(Long.class);
        return script;
    }

    private static String read(String path) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Lua 스크립트를 읽을 수 없습니다: " + path, e);
        }
    }
}
