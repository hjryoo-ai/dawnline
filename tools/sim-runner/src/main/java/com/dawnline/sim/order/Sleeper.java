package com.dawnline.sim.order;

import java.util.concurrent.TimeUnit;

/**
 * 페이싱용 대기. 주입하는 이유는 {@link java.time.Clock} 을 주입하는 이유와 같다 —
 * 시나리오 테스트가 <strong>실제로 10초를 기다리지 않게</strong> 하기 위해서다 (불변규칙 12).
 */
@FunctionalInterface
public interface Sleeper {

    /** 기본 구현. */
    Sleeper REAL = nanos -> TimeUnit.NANOSECONDS.sleep(nanos);

    /**
     * @param nanos 대기 시간(ns). 0 이하면 대기하지 않는다
     * @throws InterruptedException 대기 중 인터럽트
     */
    void sleepNanos(long nanos) throws InterruptedException;
}
