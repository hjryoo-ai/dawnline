/**
 * Redis 를 쓰는 조정(coordination) 구현.
 *
 * <p>Redis 의존은 {@code compileOnly} 다 — 이 패키지를 쓰지 않는 서비스(ops-api)가 Redis 를
 * 끌고 오지 않아야 하기 때문이다. 자동설정이 클래스 존재로 조건을 건다.
 */
package com.dawnline.messaging.redis;
