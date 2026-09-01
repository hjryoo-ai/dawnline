/**
 * ops-api — 운영자 백오피스 API. 전 토픽을 구독해 읽기 모델(CQRS 프로젝션)을 갱신하고,
 * 운영자 커맨드를 코어 서비스 REST 로 위임한다 (DESIGN.md §5.5).
 *
 * <p>코어 서비스 DB 를 직접 읽지 않는다(CLAUDE.md 불변규칙 3). 동기 REST 는
 * {@code ops-api → 코어} 방향만 허용된다(불변규칙 4).
 */
@NullMarked
package com.dawnline.ops;

import org.jspecify.annotations.NullMarked;
