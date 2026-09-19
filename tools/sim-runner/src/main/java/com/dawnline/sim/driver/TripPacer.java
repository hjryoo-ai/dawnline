package com.dawnline.sim.driver;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * 시뮬레이션 시각을 벽시계로 옮긴다 — <strong>여기서만</strong> 두 시간이 만난다.
 *
 * <p>배속({@code speed})은 「시뮬레이션 초 ÷ 벽시계 초」다. 600 이면 10분짜리 구간을 1초에
 * 지나간다. 0 이하면 아예 기다리지 않는다(테스트·빠른 확인용).
 *
 * <p><strong>배속은 페이로드에 닿지 않는다.</strong> 스캔의 {@code occurredAt} 은 계획에서
 * 파생한 시뮬레이션 시각 그대로이고, 배속이 바꾸는 것은 호출 사이의 대기뿐이다. 그래서 같은
 * seed 는 배속과 무관하게 같은 스캔 열을 낸다 — 배속이 결과를 바꾸면 그것은 결정론이 아니다.
 *
 * <p>앵커는 <strong>여정당 한 번</strong> 잡는다. 개정이 와서 계획을 다시 세워도 앵커는 그대로다 —
 * 다시 잡으면 재계획 때마다 시간이 원점으로 돌아가 뒤쪽 stop 이 점점 빨리 온다.
 */
public final class TripPacer {

    private final double speed;
    private final LongSupplier nanoTime;

    private @Nullable Instant simAnchor;
    private long wallAnchorNanos;

    /**
     * @param speed    배속. 시뮬레이션 초 ÷ 벽시계 초. 0 이하면 대기하지 않는다
     * @param nanoTime 단조 시계. 경과 시간에 벽시계를 쓰면 시간 조정에 흔들린다 (불변규칙 12)
     */
    public TripPacer(double speed, LongSupplier nanoTime) {
        this.speed = speed;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * 이 시뮬레이션 시각까지 얼마나 더 기다려야 하는가.
     *
     * @param simTime 스캔의 {@code occurredAt}
     * @return 대기할 나노초. 이미 지났거나 배속이 꺼져 있으면 0
     */
    public long waitNanosUntil(Instant simTime) {
        Objects.requireNonNull(simTime, "simTime");
        if (speed <= 0.0) {
            return 0L;
        }
        Instant anchor = simAnchor;
        if (anchor == null) {
            simAnchor = simTime;
            wallAnchorNanos = nanoTime.getAsLong();
            return 0L;
        }
        long simElapsed = Duration.between(anchor, simTime).toNanos();
        long dueAt = wallAnchorNanos + (long) (simElapsed / speed);
        return Math.max(0L, dueAt - nanoTime.getAsLong());
    }
}
