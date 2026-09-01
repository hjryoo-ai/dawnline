/**
 * tracking-service — 배송 진행 상태, ETA 갱신, 지연 위험 감지 (DESIGN.md §5.4).
 *
 * <p>발행 이벤트 {@code delivery.status}, {@code delivery.at-risk} /
 * 구독 이벤트 {@code route.assigned} (§3.2).
 */
@NullMarked
package com.dawnline.tracking;

import org.jspecify.annotations.NullMarked;
