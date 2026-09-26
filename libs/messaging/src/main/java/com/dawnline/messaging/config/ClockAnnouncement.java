package com.dawnline.messaging.config;

import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.observability.DawnlineMeters;
import com.dawnline.observability.DawnlineMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * 이 프로세스가 어떤 시각에 사는가를 기동 때 말한다 (DESIGN.md §5.6 「시뮬레이션 시계」, ADR-066 결정 2 · 3).
 *
 * <ul>
 *   <li><strong>기동 거부</strong> — 0 이 아닌 오프셋은 프로필 {@code sim} 에서만. 시계 빈이 갈아끼워진 구성(테스트의 고정 시계)에서도
 *       이 빈은 뜨므로 검사가 빠지지 않는다.</li>
 *   <li><strong>유효 시각 한 줄</strong> — 오프셋이 0 이어도 남긴다. 「이 로그는 어떤 시각에 있었는가」가 로그의 첫 질문이다.</li>
 *   <li><strong>게이지</strong> {@code dawnline_clock_offset_seconds{service}} — 서비스 다섯이 같은 값인지 {@code make obs-check} 가 대조한다.
 *       레지스트리가 없는 도구(sim-runner)는 내지 않는다.</li>
 * </ul>
 */
public class ClockAnnouncement implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(ClockAnnouncement.class);

    /** 컷오프가 사는 시간대(§2.2) — 유효 시각을 사람이 읽는 꼴로도 적는다. */
    private static final ZoneId CUTOFF_ZONE = ZoneId.of("Asia/Seoul");

    private final DawnlineClockProperties properties;
    private final Clock clock;
    private final Environment environment;
    private final @Nullable MeterRegistry registry;
    private final String service;

    /**
     * @param properties  {@code dawnline.clock.*}
     * @param clock       이 컨텍스트의 시계 빈
     * @param environment 프로필 조회
     * @param registry    게이지 레지스트리 (없으면 게이지를 내지 않는다)
     * @param service     {@code service} 태그 값
     */
    public ClockAnnouncement(DawnlineClockProperties properties, Clock clock, Environment environment,
            @Nullable MeterRegistry registry, String service) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.registry = registry;
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * 0 이 아닌 오프셋은 시뮬레이션 프로필에서만 — 「운영이면 거부」가 아니라 「시뮬레이션이라고 말한 곳에서만 허용」이다(ADR-066 결정 3).
     *
     * @param properties  {@code dawnline.clock.*}
     * @param environment 프로필 조회
     * @return 오프셋
     * @throws IllegalStateException 프로필 없이 오프셋이 있을 때
     */
    static Duration requireAllowed(DawnlineClockProperties properties, Environment environment) {
        if (!properties.isWallClock()
                && !environment.acceptsProfiles(Profiles.of(DawnlineClockProperties.SIMULATION_PROFILE))) {
            throw new IllegalStateException("dawnline.clock.offset=" + properties.offset()
                    + " 는 시뮬레이션 프로필('" + DawnlineClockProperties.SIMULATION_PROFILE + "')에서만 허용됩니다 — "
                    + "사실의 시각을 옮기는 설정이라 그 밖에서는 기동하지 않습니다(ADR-066 결정 3).");
        }
        return properties.offset();
    }

    @Override
    public void afterSingletonsInstantiated() {
        Duration offset = requireAllowed(properties, environment);
        var now = clock.instant();
        if (offset.isZero()) {
            LOG.info("시계: 유효 시각 {} ({}) — 벽시계, 오프셋 없음", now, now.atZone(CUTOFF_ZONE).toOffsetDateTime());
        } else {
            LOG.info("시계: 유효 시각 {} ({}) — 오프셋 {}, 시뮬레이션 프로필(ADR-066)", now,
                    now.atZone(CUTOFF_ZONE).toOffsetDateTime(), offset);
        }
        if (registry != null) {
            DawnlineMeters.gauge(registry, DawnlineMetrics.CLOCK_OFFSET, offset, d -> d.toMillis() / 1000.0,
                    MessagingMetrics.TAG_SERVICE, service);
        }
    }
}
