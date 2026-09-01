/**
 * fulfillment-service — FC 선택, 캠프·권역 배정, 웨이브 수명주기와 컷오프 (DESIGN.md §5.2).
 *
 * <p>발행 이벤트 {@code fulfillment.planned}, {@code wave.closed} / 구독 이벤트
 * {@code order.placed}, {@code order.cancelled} (§3.2). 내부 구조는 헥사고날(§3.4).
 */
@NullMarked
package com.dawnline.fulfillment;

import org.jspecify.annotations.NullMarked;
