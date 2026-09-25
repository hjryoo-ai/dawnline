/**
 * 트레이싱 스택(Micrometer Tracing)에 닿는 코드 — 그 클래스가 클래스패스에 있을 때만 읽힌다(DESIGN.md §9.2).
 *
 * <p>이 패키지 밖의 {@code libs/messaging} 은 트레이싱 타입을 참조하지 않는다. 트레이싱이 없는 소비자(도구)는 이 패키지를
 * 로드하지 않는다 — 자동 설정이 클래스 조건으로 가른다.
 */
package com.dawnline.messaging.tracing;
