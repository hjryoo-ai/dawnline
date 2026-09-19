package com.dawnline.sim.driver;

import static com.dawnline.sim.driver.DriverFixtures.DEPARTURE;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 시뮬레이션 시각 → 벽시계. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TripPacerTest {

    private final AtomicLong nanos = new AtomicLong();

    private TripPacer pacer(double speed) {
        return new TripPacer(speed, nanos::get);
    }

    @Test
    void 배속이_꺼져_있으면_기다리지_않는다() {
        TripPacer pacer = pacer(0.0);

        assertThat(pacer.waitNanosUntil(DEPARTURE)).isZero();
        assertThat(pacer.waitNanosUntil(DEPARTURE.plus(Duration.ofHours(4)))).isZero();
    }

    @Test
    void 첫_호출이_앵커를_잡고_그_뒤를_배속으로_나눈다() {
        TripPacer pacer = pacer(600.0);

        assertThat(pacer.waitNanosUntil(DEPARTURE)).isZero();
        // 시뮬레이션 10분 = 600초. 600배속이면 벽시계 1초다.
        assertThat(pacer.waitNanosUntil(DEPARTURE.plus(Duration.ofMinutes(10))))
                .isEqualTo(Duration.ofSeconds(1).toNanos());
    }

    @Test
    void 이미_지난_시각은_기다리지_않는다() {
        TripPacer pacer = pacer(600.0);
        pacer.waitNanosUntil(DEPARTURE);

        // 스캔 하나가 오래 걸려 벽시계가 3초 흘렀다면 다음 것은 밀지 않는다.
        nanos.set(Duration.ofSeconds(3).toNanos());

        assertThat(pacer.waitNanosUntil(DEPARTURE.plus(Duration.ofMinutes(10)))).isZero();
    }

    @Test
    void 앵커는_여정당_한_번이다() {
        // 개정이 와서 계획을 다시 세워도 앵커를 다시 잡지 않는다 — 다시 잡으면 재계획마다
        // 시간이 원점으로 돌아가 뒤쪽 stop 이 점점 빨리 온다.
        TripPacer pacer = pacer(60.0);
        pacer.waitNanosUntil(DEPARTURE);
        nanos.set(Duration.ofSeconds(10).toNanos());

        long wait = pacer.waitNanosUntil(DEPARTURE.plus(Duration.ofMinutes(30)));

        // 시뮬레이션 30분 = 1800초, 60배속 → 벽시계 30초. 이미 10초를 썼으니 20초 남았다.
        assertThat(wait).isEqualTo(Duration.ofSeconds(20).toNanos());
    }
}
