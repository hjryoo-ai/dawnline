/**
 * Redis 아웃바운드 어댑터 — 룰셋 캐시 {@code rules:camp:*:v*}, 라우트 진행 {@code route:*:progress},
 * 계획 이중 안전장치 {@code lock:plan:*} (DESIGN.md §7.2).
 *
 * <p>Redis 는 진실 저장소가 아니다(CLAUDE.md 불변규칙 7). 캐시가 비면 DB 에서 룰셋을 다시 읽는다.
 */
@NullMarked
package com.dawnline.dispatch.adapter.out.redis;

import org.jspecify.annotations.NullMarked;
