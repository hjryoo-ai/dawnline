/**
 * Redis 아웃바운드 어댑터 — 룰셋 캐시 {@code rules:camp:*:v*} (DESIGN.md §7.2).
 *
 * <p>Redis 는 진실 저장소가 아니다(CLAUDE.md 불변 규칙 7). 캐시가 비면 DB 에서 룰셋을 다시 읽는다.
 *
 * <p><strong>다시 비었다.</strong> 룰셋 캐시는 Phase 7 재검토 지점이고(계획당 룰 로딩 1회라
 * 측정 전에는 필요 없다), 이 패키지에 유일하게 살던 {@code route:*:progress} 는
 * <em>설계에서 빠졌다</em>(2026-09-23, Phase 6-0c) — 5-5 가 채우던 키인데 읽는 쪽이 끝내
 * 나타나지 않았고, {@code GET /routes/{routeId}} 가 stop 마다 살아 있는 상태를 이미 돌려주므로
 * 그 캐시를 읽는 것은 같은 사실의 둘째 출처를 만드는 일이었다. {@code lock:plan:*} 도 같은
 * 자리에서 2026-09-05 에 빠졌다 — {@code route_plans.wave_id} UNIQUE 가 이미 그 안전장치다.
 *
 * <p><strong>빠진 키를 지우지 않고 적어 두는 이유</strong>는 다음 사람이 「검토했는데 안 뒀다」와
 * 「생각하지 못했다」를 구별할 수 있게 하기 위해서다.
 */
@NullMarked
package com.dawnline.dispatch.adapter.out.redis;

import org.jspecify.annotations.NullMarked;
