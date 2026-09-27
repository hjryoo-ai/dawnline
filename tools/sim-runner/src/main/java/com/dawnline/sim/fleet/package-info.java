/**
 * 성수기 증차 — 운영자가 하는 일을 그대로 (DESIGN.md §5.6 「성수기 증차」, ADR-067).
 *
 * <p>sim-runner 는 dispatch 에 직접 닿지 않는다. 기준(몇 대가 모자라는가)은 dispatch 가 자기 후보로 내고, 이 패키지는 그것을
 * <strong>ops-api 를 거쳐</strong> 운영자의 토큰으로 읽고 더하고 뺀다 — 증차는 운영자의 커맨드이고 감사 행이 남아야 한다
 * (ADR-055 가 닫은 「감사 없는 둘째 운영자」를 다시 열지 않는다).
 */
@NullMarked
package com.dawnline.sim.fleet;

import org.jspecify.annotations.NullMarked;
