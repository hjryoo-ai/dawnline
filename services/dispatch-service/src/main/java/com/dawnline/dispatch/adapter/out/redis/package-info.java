/**
 * Redis 아웃바운드 어댑터 — 룰셋 캐시 {@code rules:camp:*:v*}, 라우트 진행
 * {@code route:*:progress} (DESIGN.md §7.2).
 *
 * <p>Redis 는 진실 저장소가 아니다(CLAUDE.md 불변규칙 7). 캐시가 비면 DB 에서 룰셋을 다시 읽는다.
 *
 * <p><strong>아직 비어 있다.</strong> 룰셋 캐시는 Phase 7 재검토 지점이고(계획당 룰 로딩 1회라
 * 측정 전에는 필요 없다), 라우트 진행은 tracking 이 이벤트를 내는 Phase 5-5 의 몫이다.
 * {@code lock:plan:*} 는 2026-09-05 에 <em>설계에서 빠졌다</em> — {@code route_plans.wave_id}
 * UNIQUE 가 이미 그 안전장치다.
 */
@NullMarked
package com.dawnline.dispatch.adapter.out.redis;

import org.jspecify.annotations.NullMarked;
