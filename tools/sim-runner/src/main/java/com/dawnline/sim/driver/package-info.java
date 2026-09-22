/**
 * 기사 시뮬레이터 (DESIGN.md §5.6, IMPLEMENTATION_PLAN Phase 5-2).
 *
 * <p>{@code route.assigned} 를 구독해 라우트 하나를 기사 한 명처럼 돈다 — 캠프를 출발하고,
 * stop 을 순서대로 방문하고, 도착·완료·실패를 tracking 의 스캔 API 로 보고한다.
 *
 * <h2>시뮬레이션 시각과 벽시계는 다른 것이다</h2>
 * 스캔의 {@code occurredAt} 은 <strong>계획에서 파생</strong>한다 — {@code plannedDeparture} 와
 * {@code plannedArrival} 에 seed 에서 뽑은 지연을 더한 값이다. {@code Instant.now()} 가 아니다
 * (불변규칙 12). 그래야 <em>주입한 지연이 곧 tracking 이 계산하는 편차</em>가 되고,
 * {@code late-injection} 시나리오가 「늦었다」를 값으로 어설션할 수 있다. 벽시계는
 * {@link com.dawnline.sim.driver.TripPacer} 의 배속에만 쓰이며 페이로드에 닿지 않는다.
 *
 * <p><strong>그래서 압축된 시간에서는 at-risk 가 라우트당 한 번만 보인다.</strong> tracking 의
 * 쿨다운 키({@code route:{id}:atrisk:cooldown})는 TTL 이 <em>벽시계</em> 5분인데 시뮬레이션
 * 시각은 배속만큼 빨리 흐르기 때문이다. 이것은 <strong>시뮬레이터의 제약이지 tracking 의
 * 규칙이 아니다</strong> — {@code late-injection} 의 어설션은 「at-risk 가 났다」까지이고,
 * 「몇 번 났다」는 배속 1에서만 의미가 있다 (ADR-046: 쿨다운이 지키는 것은 알림 수다).
 *
 * <h2>이 도구에는 {@code processed_events} 가 없다</h2>
 * 불변규칙 2 의 예외이며, 성립하는 이유는 「도구라서」가 아니라 <strong>하류가 멱등이라서</strong>다 —
 * 중복 소비가 만드는 것은 tracking 으로 가는 중복 스캔이고, 그것은 §8.5 의
 * 「{@code (routeId, seq, type)} + 상태 머신」이 {@code STALE} 로 흡수한다. 하류가 멱등이 아닌
 * 도구는 같은 예외를 쓸 수 없다. 이 도구가 하는 것은 {@link com.dawnline.sim.driver.DriverFleet}
 * 의 개정 단조 증가 검사뿐이고, 그것은 tracking 의 {@code route_revisions} 와 같은 모양이다
 * (DESIGN.md §13 매핑표 규칙 2).
 */
@NullMarked
package com.dawnline.sim.driver;

import org.jspecify.annotations.NullMarked;
