/**
 * 아웃바운드 포트 — 영속성·메시징·캐시 어댑터가 구현하는 인터페이스 (DESIGN.md §3.4).
 *
 * <p>포트는 애플리케이션이 소유하고 어댑터가 구현한다. 의존 방향은 항상
 * {@code adapter → application → domain} 이다.
 */
@NullMarked
package com.dawnline.fulfillment.application.port.out;

import org.jspecify.annotations.NullMarked;
