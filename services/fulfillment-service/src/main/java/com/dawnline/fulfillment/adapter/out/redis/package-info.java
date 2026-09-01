/**
 * Redis 아웃바운드 어댑터 — {@code geo:fc}/{@code geo:camp}(GEO), {@code zone:geohash5:*} 캐시,
 * 컷오프 락 {@code lock:wave:*} (DESIGN.md §7.2).
 *
 * <p>Redis 는 진실 저장소가 아니다(CLAUDE.md 불변규칙 7). GEO 키가 비면 DB 전체 조회 후
 * 메모리 하버사인으로, 락이 없으면 DB 낙관적 락으로 이중 마감을 막는다.
 */
@NullMarked
package com.dawnline.fulfillment.adapter.out.redis;

import org.jspecify.annotations.NullMarked;
