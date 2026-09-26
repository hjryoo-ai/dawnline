package com.dawnline.messaging.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code dawnline.clock.*} — 주입 시계의 오프셋 (DESIGN.md §5.6 「시뮬레이션 시계」, ADR-066).
 *
 * <p>시뮬레이션은 스케줄이 아니라 시계를 옮긴다. 컷오프 상수({@code TierSchedule})도 파이프라인도 그대로 두고, 다섯 서비스와
 * sim-runner 의 주입 시계에 같은 오프셋을 더해 「실행 시작이 22:40 KST」를 아무 때나 만든다. 값은 compose 앵커의 환경변수 하나
 * ({@code DAWNLINE_CLOCK_OFFSET})에서 온다.
 *
 * <p>0 이 아닌 오프셋은 Spring 프로필 {@value #SIMULATION_PROFILE} 에서만 허용된다 — 아니면 기동을 거부한다(ADR-066 결정 3).
 *
 * @param offset 벽시계에 더할 기간 (기본 0)
 */
@ConfigurationProperties(prefix = "dawnline.clock", ignoreUnknownFields = false)
public record DawnlineClockProperties(@DefaultValue("PT0S") Duration offset) {

    /** 오프셋을 허용하는 유일한 프로필. */
    public static final String SIMULATION_PROFILE = "sim";

    public DawnlineClockProperties {
        Objects.requireNonNull(offset, "offset");
    }

    /** @return 오프셋이 0 인가 */
    public boolean isWallClock() {
        return offset.isZero();
    }
}
